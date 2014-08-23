package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.commanders.emails.EmailOptOutCommander
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ LocalPostOffice, SystemEmailAddress, ElectronicMail, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time.Clock
import com.keepit.model._
import com.keepit.social.BasicUser
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.logging.{ AccessLog, Logging }

import scala.concurrent.duration.Duration
import scala.util.Sorting

class LibraryCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionRepo: CollectionRepo,
    postOffice: LocalPostOffice,
    s3ImageStore: S3ImageStore,
    emailOptOutCommander: EmailOptOutCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends Logging {

  def createFullLibraryInfo(library: Library): FullLibraryInfo = {
    val (lib, owner, collabs, follows, keeps) = db.readOnlyReplica { implicit s =>
      val owner = basicUserRepo.load(library.ownerId)
      val memberships = libraryMembershipRepo.getWithLibraryId(library.id.get)
      val (collabs, follows) = memberships.foldLeft(List.empty[BasicUser], List.empty[BasicUser]) {
        case ((c1, f1), m) => m.access match {
          case LibraryAccess.READ_ONLY => (c1, basicUserRepo.load(m.userId) :: f1)
          case LibraryAccess.READ_INSERT => (basicUserRepo.load(m.userId) :: c1, f1)
          case LibraryAccess.READ_WRITE => (basicUserRepo.load(m.userId) :: c1, f1)
          case _ => (c1, f1)
        }
      }
      val keeps = keepRepo.getByLibrary(library.id.get).map(KeepInfo.fromKeep)
      (library, owner, collabs, follows, keeps)
    }

    FullLibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      ownerId = owner.externalId,
      description = lib.description,
      slug = lib.slug,
      url = Library.formatLibraryUrl(owner.username, owner.externalId, lib.slug),
      visibility = lib.visibility,
      collaborators = collabs, // todo(andrew): should only be first `x` collaborators
      followers = follows, // todo(andrew): should only be first `x` followers
      keeps = keeps, // todo(andrew): should only be first `x` keeps
      numKeeps = keeps.length, // todo(andrew): should be the total number of keeps in the library
      numCollaborators = collabs.length, // todo(andrew): should be the total number of collaborators in the library
      numFollowers = follows.length) // todo(andrew): should be the total number of followers in the library
  }

  def addLibrary(libAddReq: LibraryAddRequest, ownerId: Id[User]): Either[LibraryFail, Library] = {
    val badMessage: Option[String] = {
      if (!libAddReq.collaborators.intersect(libAddReq.followers).isEmpty) { Some("collaborators & followers overlap!") }
      else if (libAddReq.name.isEmpty || !Library.isValidName(libAddReq.name)) { Some("invalid library name") }
      else if (libAddReq.slug.isEmpty || !LibrarySlug.isValidSlug(libAddReq.slug)) { Some("invalid library slug") }
      else { None }
    }
    badMessage match {
      case Some(x) => Left(LibraryFail(x))
      case _ => {
        val exists = db.readOnlyReplica { implicit s => libraryRepo.getByNameAndUserId(ownerId, libAddReq.name) }
        exists match {
          case Some(x) => Left(LibraryFail("library name already exists for user"))
          case _ => {
            val (collaboratorIds, followerIds) = db.readOnlyReplica { implicit s =>
              val collabs = libAddReq.collaborators.map { x =>
                val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
                inviteeIdOpt.get
              }
              val follows = libAddReq.followers.map { x =>
                val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
                inviteeIdOpt.get
              }
              (collabs, follows)
            }
            val validVisibility = libAddReq.visibility
            val validSlug = LibrarySlug(libAddReq.slug)

            val library = db.readWrite { implicit s =>
              val lib = libraryRepo.save(Library(ownerId = ownerId, name = libAddReq.name, description = libAddReq.description,
                visibility = validVisibility, slug = validSlug, kind = LibraryKind.USER_CREATED, memberCount = 1))
              val libId = lib.id.get
              libraryMembershipRepo.save(LibraryMembership(libraryId = libId, userId = ownerId, access = LibraryAccess.OWNER, showInSearch = true))
              lib
            }

            val bulkInvites1 = for (c <- collaboratorIds) yield LibraryInvite(libraryId = library.id.get, ownerId = ownerId, userId = Some(c), access = LibraryAccess.READ_WRITE)
            val bulkInvites2 = for (c <- followerIds) yield LibraryInvite(libraryId = library.id.get, ownerId = ownerId, userId = Some(c), access = LibraryAccess.READ_ONLY)

            inviteBulkUsers(bulkInvites1 ++ bulkInvites2)
            Right(library)
          }
        }
      }
    }
  }

  def modifyLibrary(libraryId: Id[Library], userId: Id[User],
    name: Option[String] = None,
    description: Option[String] = None,
    slug: Option[String] = None,
    visibility: Option[LibraryVisibility] = None): Either[LibraryFail, Library] = {

    db.readWrite { implicit s =>
      val targetLib = libraryRepo.get(libraryId)
      if (targetLib.ownerId != userId) {
        Left(LibraryFail("Not Owner"))
      } else {
        def validName(name: String): Either[LibraryFail, String] = {
          if (Library.isValidName(name)) Right(name)
          else Left(LibraryFail("Invalid name"))
        }
        def validSlug(slug: String): Either[LibraryFail, String] = {
          if (LibrarySlug.isValidSlug(slug)) Right(slug)
          else Left(LibraryFail("Invalid slug"))
        }

        for {
          newName <- validName(name.getOrElse(targetLib.name)).right
          newSlug <- validSlug(slug.getOrElse(targetLib.slug.value)).right
        } yield {
          val newDescription: Option[String] = description.orElse(targetLib.description)
          val newVisibility: LibraryVisibility = visibility.getOrElse(targetLib.visibility)
          libraryRepo.save(targetLib.copy(name = newName, slug = LibrarySlug(newSlug), visibility = newVisibility, description = newDescription))
        }
      }
    }
  }

  def removeLibrary(libraryId: Id[Library], userId: Id[User]): Either[LibraryFail, String] = {
    db.readWrite { implicit s =>
      val oldLibrary = libraryRepo.get(libraryId)
      if (oldLibrary.ownerId != userId) {
        Left(LibraryFail("Not Owner"))
      } else {
        val removedLibrary = libraryRepo.save(oldLibrary.withState(LibraryStates.INACTIVE))

        libraryMembershipRepo.getWithLibraryId(removedLibrary.id.get).map { m =>
          libraryMembershipRepo.save(m.withState(LibraryMembershipStates.INACTIVE))
        }
        libraryInviteRepo.getWithLibraryId(removedLibrary.id.get).map { inv =>
          libraryInviteRepo.save(inv.withState(LibraryInviteStates.INACTIVE))
        }
        Right("success")
      }
    }
  }

  def copyKeepsFromCollectionToLibrary(libraryId: Id[Library], tagName: String): Either[LibraryFail, Seq[(Keep, LibraryError)]] = {
    val (library, ownerId, memTo, tagOpt, keeps) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(libraryId)
      val ownerId = library.ownerId
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, ownerId)
      val tagOpt = collectionRepo.getByUserAndName(ownerId, tagName)
      val keeps = tagOpt match {
        case None => Seq.empty
        case Some(tag) => keepToCollectionRepo.getByCollection(tag.id.get).map(k2c => keepRepo.get(k2c.keepId))
      }
      (library, ownerId, memTo, tagOpt, keeps)
    }
    (memTo, tagOpt) match {
      case (_, None) => Left(LibraryFail("tag not found"))
      case (v, _) if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        Right(keeps.map(_ -> LibraryError.DestPermissionDenied).toSeq)
      case (_, Some(tag)) =>
        def saveKeep(k: Keep, s: RWSession): Unit = {
          implicit val session = s
          val newKeep = keepRepo.save(Keep(title = k.title, uriId = k.uriId, url = k.url, urlId = k.urlId, isPrivate = k.isPrivate,
            userId = k.userId, source = KeepSource.tagImport, libraryId = Some(libraryId)))
          keepToCollectionRepo.save(KeepToCollection(keepId = newKeep.id.get, collectionId = tag.id.get))
        }
        val badKeeps = applyToKeeps(ownerId, library, keeps, Set(), saveKeep)
        Right(badKeeps.toSeq)
    }
  }

  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryAccess, Library)], Seq[(LibraryInvite, Library)]) = {
    db.readOnlyMaster { implicit s =>
      val myLibraries = libraryRepo.getByUser(userId)
      val myInvites = libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.ACCEPTED, LibraryInviteStates.INACTIVE))
      (myLibraries, myInvites)
    }
  }

  def userAccess(userId: Id[User], libraryId: Id[Library], universalLinkOpt: Option[String]): Option[LibraryAccess] = {
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) match {
        case Some(mem) =>
          Some(mem.access)
        case None =>
          val lib = libraryRepo.get(libraryId)
          if (lib.visibility == LibraryVisibility.PUBLISHED)
            Some(LibraryAccess.READ_ONLY)
          else if (libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId).nonEmpty)
            Some(LibraryAccess.READ_ONLY)
          else if (universalLinkOpt.nonEmpty && lib.universalLink == universalLinkOpt)
            Some(LibraryAccess.READ_ONLY)
          else
            None
      }
    }
  }

  private def inviteBulkUsers(invites: Seq[LibraryInvite]) {
    db.readWrite { implicit s =>
      invites.map { invite => libraryInviteRepo.save(invite) }
    }
  }

  def internSystemGeneratedLibraries(userId: Id[User]): (Library, Library) = { // returns true if created, false if already existed
    db.readWrite { implicit session =>
      val libMem = libraryMembershipRepo.getWithUserId(userId, None)
      val allLibs = libraryRepo.getByUser(userId, None)

      // Get all current system libraries, for main/secret, make sure only one is active.
      // This corrects any issues with previously created libraries / memberships
      val sysLibs = allLibs.filter(_._2.ownerId == userId)
        .filter(l => l._2.kind == LibraryKind.SYSTEM_MAIN || l._2.kind == LibraryKind.SYSTEM_SECRET)
        .sortBy(_._2.id.get.id)
        .groupBy(_._2.kind)
        .map {
          case (kind, libs) =>
            val (slug, name, visibility) = if (kind == LibraryKind.SYSTEM_MAIN) ("main", "Main Library", LibraryVisibility.DISCOVERABLE) else ("secret", "Secret Library", LibraryVisibility.SECRET)

            val activeLib = libs.head._2.copy(state = LibraryStates.ACTIVE, slug = LibrarySlug(slug), name = name, visibility = visibility, memberCount = 1)
            val activeMembership = libMem.find(m => m.libraryId == activeLib.id.get && m.access == LibraryAccess.OWNER)
              .getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
              .copy(state = LibraryMembershipStates.ACTIVE)
            val active = (activeMembership, activeLib)
            val otherLibs = libs.tail.map {
              case (a, l) =>
                val inactMem = libMem.find(_.libraryId == l.id.get)
                  .getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
                  .copy(state = LibraryMembershipStates.INACTIVE)
                (inactMem, l.copy(state = LibraryStates.INACTIVE))
            }
            active +: otherLibs
        }.flatten.toList // force eval

      // Save changes
      sysLibs.map {
        case (mem, lib) =>
          libraryRepo.save(lib)
          libraryMembershipRepo.save(mem)
      }

      // If user is missing a system lib, create it
      val mainOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).isEmpty) {
        val mainLib = libraryRepo.save(Library(name = "Main Library", ownerId = userId, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("main"), kind = LibraryKind.SYSTEM_MAIN, memberCount = 1))
        val mainMem = libraryMembershipRepo.save(LibraryMembership(libraryId = mainLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
        Some(mainLib)
      } else None

      val secretOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).isEmpty) {
        val secretLib = libraryRepo.save(Library(name = "Secret Library", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), kind = LibraryKind.SYSTEM_SECRET, memberCount = 1))
        val secretMem = libraryMembershipRepo.save(LibraryMembership(libraryId = secretLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
        Some(secretLib)
      } else None

      (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).map(_._2).orElse(mainOpt).get, sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).map(_._2).orElse(secretOpt).get)
    }
  }

  def inviteUsersToLibrary(libraryId: Id[Library], inviterId: Id[User], inviteList: Seq[(Either[Id[User], EmailAddress], LibraryAccess)]): Either[LibraryFail, Seq[(Either[ExternalId[User], EmailAddress], LibraryAccess)]] = {
    db.readWrite { implicit s =>
      val targetLib = libraryRepo.get(libraryId)
      if (targetLib.ownerId != inviterId) {
        Left(LibraryFail("Not Owner"))
      } else {
        val successInvites = for (i <- inviteList) yield {
          val (inv, extId) = i._1 match {
            case Left(id) =>
              (LibraryInvite(libraryId = libraryId, ownerId = inviterId, userId = Some(id), access = i._2), Left(userRepo.get(id).externalId))
            case Right(email) =>
              (LibraryInvite(libraryId = libraryId, ownerId = inviterId, emailAddress = Some(email), access = i._2), Right(email))
          }
          (inv, (extId, i._2))
        }
        val (inv1, res) = successInvites.unzip
        inviteBulkUsers(inv1)
        Right(res)
      }
    }
  }

  def joinLibrary(userId: Id[User], libraryId: Id[Library]): Either[LibraryFail, Library] = {
    db.readWrite { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val listInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId)

      if (lib.visibility != LibraryVisibility.PUBLISHED && listInvites.isEmpty) {
        Left(LibraryFail("cannot join - not published library"))
      } else {
        val maxAccess = if (listInvites.isEmpty) LibraryAccess.READ_ONLY else listInvites.sorted.last.access
        libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = userId, access = maxAccess, showInSearch = true))
        listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED)))
        Right(lib)
      }
    }
  }

  def declineLibrary(userId: Id[User], libraryId: Id[Library]) = {
    db.readWrite { implicit s =>
      val listInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libraryId, userId = userId)
      listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.DECLINED)))
    }
  }

  def leaveLibrary(libraryId: Id[Library], userId: Id[User]): Either[LibraryFail, Unit] = {
    db.readWrite { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) match {
        case None => Left(LibraryFail("membership not found"))
        case Some(mem) => {
          libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.INACTIVE))
          Right()
        }
      }
    }
  }

  def getKeeps(libraryId: Id[Library]): Seq[Keep] = {
    db.readOnlyMaster { implicit s =>
      keepRepo.getByLibrary(libraryId)
    }
  }

  // Return is Set of Keep -> error message
  private def applyToKeeps(userId: Id[User],
    library: Library,
    keeps: Seq[Keep],
    excludeFromAccess: Set[LibraryAccess],
    saveKeep: (Keep, RWSession) => Unit): Seq[(Keep, LibraryError)] = {

    val badKeeps = collection.mutable.Set[(Keep, LibraryError)]()
    db.readWrite { implicit s =>
      val existingURIs = keepRepo.getByLibrary(library.id.get).map(_.uriId).toSet
      keeps.groupBy(_.libraryId).map {
        case (None, keeps) => keeps
        case (Some(fromLibraryId), keeps) =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(fromLibraryId, userId) match {
            case None =>
              badKeeps ++= keeps.map(_ -> LibraryError.SourcePermissionDenied)
              Seq[Keep]()
            case Some(memFrom) if excludeFromAccess.contains(memFrom.access) =>
              badKeeps ++= keeps.map(_ -> LibraryError.SourcePermissionDenied)
              Seq[Keep]()
            case Some(_) =>
              keeps
          }
      }.flatten.foreach { keep =>
        if (!existingURIs.contains(keep.uriId)) {
          saveKeep(keep, s)
        } else {
          badKeeps += keep -> LibraryError.AlreadyExistsInDest
        }
      }
    }
    badKeeps.toSeq
  }

  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep]): Seq[(Keep, LibraryError)] = {
    val (library, memTo) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(toLibraryId)
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId)
      (library, memTo)
    }
    memTo match {
      case v if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        keeps.map(_ -> LibraryError.DestPermissionDenied)
      case Some(_) =>
        def saveKeep(k: Keep, s: RWSession): Unit = {
          implicit val session = s
          val newKeep = keepRepo.save(Keep(title = k.title, uriId = k.uriId, url = k.url, urlId = k.urlId, isPrivate = k.isPrivate,
            userId = k.userId, source = k.source, libraryId = Some(toLibraryId)))
          keepToCollectionRepo.getByKeep(k.id.get).map { k2c =>
            keepToCollectionRepo.save(KeepToCollection(keepId = newKeep.id.get, collectionId = k2c.collectionId))
          }
        }

        val badKeeps = applyToKeeps(userId, library, keeps, Set(), saveKeep)
        badKeeps
    }
  }

  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep]): Seq[(Keep, LibraryError)] = {
    val (library, memTo) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(toLibraryId)
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId)
      (library, memTo)
    }
    memTo match {
      case v if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        keeps.map(_ -> LibraryError.DestPermissionDenied)
      case Some(_) =>

        def saveKeep(k: Keep, s: RWSession): Unit = {
          implicit val session = s
          keepRepo.save(k.copy(libraryId = Some(toLibraryId)))
        }

        val badKeeps = applyToKeeps(userId, library, keeps, Set(LibraryAccess.READ_ONLY, LibraryAccess.READ_INSERT), saveKeep)
        badKeeps
    }
  }

  def inviteNotification(inviterId: Id[User], inviteeId: Id[User], libraryId: Id[Library]): Unit = {
    val (inviter, invitee, library, owner) = db.readOnlyMaster { implicit s =>
      val inviter = userRepo.get(inviterId)
      val invitee = userRepo.get(inviteeId)
      val library = libraryRepo.get(libraryId)
      val owner = userRepo.get(library.ownerId)
      (inviter, invitee, library, owner)
    }
    invitee.primaryEmail match {
      case None => {}
      case Some(email) =>
        val libraryLink = s"""www.kifi.com/${owner.username.getOrElse(owner.externalId)}/${library.slug}?auth=${library.universalLink}"""

        val imageUrl = s3ImageStore.avatarUrlByExternalId(Some(200), inviter.externalId, inviter.pictureName.getOrElse("0"), Some("https"))
        val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(email))}"
        db.readWrite { implicit session =>
          postOffice.sendMail(ElectronicMail(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Seq(email),
            subject = "Kifi.com | You've been invited to a library!",
            htmlBody = views.html.email.libraryInvitation(invitee.firstName, inviter, imageUrl, library.name, library.description, libraryLink, unsubLink).body,
            category = NotificationCategory.User.LIBRARY_INVITATION
          ))
        }
    }
  }
}

sealed abstract class LibraryError(val message: String)
object LibraryError {
  case object SourcePermissionDenied extends LibraryError("source_permission_denied")
  case object DestPermissionDenied extends LibraryError("dest_permission_denied")
  case object AlreadyExistsInDest extends LibraryError("already_exists_in_dest")

  def apply(message: String): LibraryError = {
    message match {
      case SourcePermissionDenied.message => SourcePermissionDenied
      case DestPermissionDenied.message => DestPermissionDenied
      case AlreadyExistsInDest.message => AlreadyExistsInDest
    }
  }
}

case class LibraryFail(message: String) extends AnyVal

@json case class LibraryAddRequest(
  name: String,
  visibility: LibraryVisibility,
  description: Option[String] = None,
  slug: String,
  collaborators: Seq[ExternalId[User]],
  followers: Seq[ExternalId[User]])

case class LibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  shortDescription: Option[String],
  url: String,
  ownerId: ExternalId[User],
  numKeeps: Int)
object LibraryInfo {
  implicit val libraryExternalIdFormat = ExternalId.format[Library]

  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'shortDescription).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'ownerId).format[ExternalId[User]] and
    (__ \ 'numKeeps).format[Int]
  )(LibraryInfo.apply, unlift(LibraryInfo.unapply))

  def fromLibraryAndOwner(lib: Library, owner: User, keepCount: Int)(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      url = Library.formatLibraryUrl(owner.username, owner.externalId, lib.slug),
      ownerId = owner.externalId,
      numKeeps = keepCount
    )
  }

  val MaxDescriptionLength = 120
  def descriptionShortener(str: Option[String]): Option[String] = str match {
    case Some(s) => { Some(s.dropRight(s.length - MaxDescriptionLength)) } // will change later!
    case _ => None
  }
}

private case class GroupHolder(count: Int, users: Seq[BasicUser], isMore: Boolean)
private object GroupHolder {
  implicit val format = (
    (__ \ 'count).format[Int] and
    (__ \ 'users).format[Seq[BasicUser]] and
    (__ \ 'isMore).format[Boolean]
  )(GroupHolder.apply, unlift(GroupHolder.unapply))
}

private case class KeepsHolder(count: Int, keeps: Seq[KeepInfo], isMore: Boolean)
private object KeepsHolder {
  implicit val format = (
    (__ \ 'count).format[Int] and
    (__ \ 'keeps).format[Seq[KeepInfo]] and
    (__ \ 'isMore).format[Boolean]
  )(KeepsHolder.apply, unlift(KeepsHolder.unapply))
}

case class FullLibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  description: Option[String],
  slug: LibrarySlug,
  url: String,
  ownerId: ExternalId[User],
  collaborators: Seq[BasicUser],
  followers: Seq[BasicUser],
  keeps: Seq[KeepInfo],
  numKeeps: Int,
  numCollaborators: Int,
  numFollowers: Int)

object FullLibraryInfo {
  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'url).format[String] and
    (__ \ 'ownerId).format[ExternalId[User]] and
    (__ \ 'collaborators).format[Seq[BasicUser]] and
    (__ \ 'followers).format[Seq[BasicUser]] and
    (__ \ 'keeps).format[Seq[KeepInfo]] and
    (__ \ 'numKeeps).format[Int] and
    (__ \ 'numCollaborators).format[Int] and
    (__ \ 'numFollowers).format[Int]
  )(FullLibraryInfo.apply, unlift(FullLibraryInfo.unapply))
}

case class LibraryInfoIdKey(libraryId: Id[Library]) extends Key[LibraryInfo] {
  override val version = 1
  val namespace = "library_info_libraryid"
  def toKey(): String = libraryId.id.toString
}

class LibraryInfoIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[LibraryInfoIdKey, LibraryInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
