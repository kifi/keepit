package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.Clock
import com.keepit.model._
import com.keepit.social.BasicUser
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.logging.{ AccessLog, Logging }

import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }

class LibraryCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends Logging {

  def createFullLibraryInfo(library: Library): FullLibraryInfo = {
    val (lib, owner, collabs, follows, numKeeps) = db.readOnlyReplica { implicit s =>
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
      val numKeeps = keepRepo.getByLibrary(library.id.get).length
      (library, owner, collabs, follows, numKeeps)
    }
    val collabGroup = GroupHolder(count = collabs.length, users = collabs, isMore = false)
    val followerGroup = GroupHolder(count = follows.length, users = follows, isMore = false)
    FullLibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      ownerId = owner.externalId,
      description = lib.description,
      slug = lib.slug,
      visibility = lib.visibility,
      collaborators = collabGroup,
      followers = followerGroup,
      keepCount = numKeeps)
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
        val exists = db.readOnlyReplica { implicit s => libraryRepo.getByNameAndUser(ownerId, libAddReq.name) }
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
                visibility = validVisibility, slug = validSlug, kind = LibraryKind.USER_CREATED, isSearchableByOthers = libAddReq.isSearchableByOthers))
              val libId = lib.id.get
              libraryMembershipRepo.save(LibraryMembership(libraryId = libId, userId = ownerId, access = LibraryAccess.OWNER, showInSearch = true))
              lib
            }

            val bulkInvites1 = for (c <- collaboratorIds) yield LibraryInvite(libraryId = library.id.get, ownerId = ownerId, userId = c, access = LibraryAccess.READ_WRITE)
            val bulkInvites2 = for (c <- followerIds) yield LibraryInvite(libraryId = library.id.get, ownerId = ownerId, userId = c, access = LibraryAccess.READ_ONLY)

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

  def getLibraryById(id: Id[Library]): Library = {
    db.readOnlyMaster { implicit s =>
      libraryRepo.get(id)
    }
  }

  def getLibrariesByUser(userId: Id[User]): Seq[(LibraryAccess, Library)] = {
    db.readOnlyMaster { implicit s =>
      libraryRepo.getByUser(userId)
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
            val (slug, name, searchableByOthers) = if (kind == LibraryKind.SYSTEM_MAIN) ("main", "Main Library", true) else ("secret", "Secret Library", false)

            val activeLib = libs.head._2.copy(state = LibraryStates.ACTIVE, slug = LibrarySlug(slug), name = name, visibility = LibraryVisibility.SECRET, isSearchableByOthers = searchableByOthers)
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
        val mainLib = libraryRepo.save(Library(name = "Main Library", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("main"), kind = LibraryKind.SYSTEM_MAIN, isSearchableByOthers = true))
        val mainMem = libraryMembershipRepo.save(LibraryMembership(libraryId = mainLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
        Some(mainLib)
      } else None

      val secretOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).isEmpty) {
        val secretLib = libraryRepo.save(Library(name = "Secret Library", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), kind = LibraryKind.SYSTEM_SECRET, isSearchableByOthers = false))
        val secretMem = libraryMembershipRepo.save(LibraryMembership(libraryId = secretLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
        Some(secretLib)
      } else None

      (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).map(_._2).orElse(mainOpt).get, sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).map(_._2).orElse(secretOpt).get)
    }
  }

  def inviteUsersToLibrary(libraryId: Id[Library], inviterId: Id[User], inviteList: Seq[(Id[User], LibraryAccess)]): Either[LibraryFail, Seq[(ExternalId[User], LibraryAccess)]] = {
    db.readWrite { implicit s =>
      val targetLib = libraryRepo.get(libraryId)
      if (targetLib.ownerId != inviterId) {
        Left(LibraryFail("Not Owner"))
      } else {
        val successInvites = for (i <- inviteList) yield {
          val inv = LibraryInvite(libraryId = libraryId, ownerId = inviterId, userId = i._1, access = i._2)
          val extId = userRepo.get(i._1).externalId
          (inv, (extId, i._2))
        }
        val (inv1, res) = successInvites.unzip
        inviteBulkUsers(inv1)
        Right(res)
      }
    }
  }

  def joinLibrary(inviteId: Id[LibraryInvite]): Library = {
    db.readWrite { implicit s =>
      val inv = libraryInviteRepo.get(inviteId)
      val listInvites = libraryInviteRepo.getWithLibraryIdandUserId(inv.libraryId, inv.userId)

      listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED)))

      val listAccesses = listInvites.map(_.access)
      val maxAccess = if (listAccesses.contains(LibraryAccess.READ_WRITE)) {
        LibraryAccess.READ_WRITE
      } else if (listAccesses.contains(LibraryAccess.READ_INSERT)) {
        LibraryAccess.READ_INSERT
      } else {
        LibraryAccess.READ_ONLY
      }

      libraryMembershipRepo.save(LibraryMembership(libraryId = inv.libraryId, userId = inv.userId, access = maxAccess, showInSearch = true))
      libraryRepo.get(inv.libraryId)
    }
  }

  def declineLibrary(inviteId: Id[LibraryInvite]) = {
    db.readWrite { implicit s =>
      val inv = libraryInviteRepo.get(inviteId)
      libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.DECLINED))
    }
  }

  def leaveLibrary(libraryId: Id[Library], userId: Id[User]): Either[LibraryFail, Unit] = {
    db.readWrite { implicit s =>
      libraryMembershipRepo.getWithLibraryIdandUserId(libraryId, userId) match {
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

  def copyKeeps(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library], keeps: Set[Id[Keep]]): Either[LibraryFail, Library] = {
    db.readWrite { implicit s =>
      val memberFrom = libraryMembershipRepo.getWithLibraryIdandUserId(fromLibraryId, userId)
      val memberTo = libraryMembershipRepo.getWithLibraryIdandUserId(toLibraryId, userId)

      (memberFrom, memberTo) match {
        case (None, _) => Left(LibraryFail("no membership from library"))
        case (_, None) => Left(LibraryFail("no membership to library"))
        case (Some(_), Some(memTo)) if memTo.access == LibraryAccess.READ_ONLY => Left(LibraryFail("invalid access to library"))
        case (_, _) => {
          val existingURIs = keepRepo.getByLibrary(toLibraryId).map(_.uriId)
          keeps.map { keepId =>
            val oldKeep = keepRepo.get(keepId)
            if (!existingURIs.contains(oldKeep.uriId)) {
              keepRepo.save(Keep(title = oldKeep.title, uriId = oldKeep.uriId, url = oldKeep.url, urlId = oldKeep.urlId,
                userId = oldKeep.userId, source = oldKeep.source, libraryId = Some(toLibraryId)))
            }
          }
          Right(libraryRepo.get(toLibraryId))
        }
      }
    }
  }

  def moveKeeps(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library], keeps: Set[Id[Keep]]): Either[LibraryFail, Library] = {
    db.readWrite { implicit s =>
      val memberFrom = libraryMembershipRepo.getWithLibraryIdandUserId(fromLibraryId, userId)
      val memberTo = libraryMembershipRepo.getWithLibraryIdandUserId(toLibraryId, userId)

      (memberFrom, memberTo) match {
        case (None, _) => Left(LibraryFail("no membership from library"))
        case (_, None) => Left(LibraryFail("no membership to library"))
        case (Some(memFrom), _) if (memFrom.access == LibraryAccess.READ_ONLY || memFrom.access == LibraryAccess.READ_INSERT) => Left(LibraryFail("invalid access to library"))
        case (_, Some(memTo)) if memTo.access == LibraryAccess.READ_ONLY => Left(LibraryFail("invalid access to library"))
        case (_, _) => {
          val existingURIs = keepRepo.getByLibrary(toLibraryId).map(_.uriId)
          keeps.map { keepId =>
            val oldKeep = keepRepo.get(keepId)
            if (!existingURIs.contains(oldKeep.uriId)) {
              keepRepo.save(oldKeep.copy(libraryId = Some(toLibraryId)))
            }
          }
          Right(libraryRepo.get(toLibraryId))
        }
      }
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
  followers: Seq[ExternalId[User]],
  isSearchableByOthers: Boolean)

case class LibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  shortDescription: Option[String],
  slug: LibrarySlug,
  ownerId: ExternalId[User])
object LibraryInfo {
  implicit val libraryExternalIdFormat = ExternalId.format[Library]

  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'shortDescription).formatNullable[String] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'ownerId).format[ExternalId[User]]
  )(LibraryInfo.apply, unlift(LibraryInfo.unapply))

  def fromLibraryAndOwner(lib: Library, owner: User)(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      slug = lib.slug,
      ownerId = owner.externalId
    )
  }

  val MaxDescriptionLength = 120
  def descriptionShortener(str: Option[String]): Option[String] = str match {
    case Some(s) => { Some(s.dropRight(s.length - MaxDescriptionLength)) } // will change later!
    case _ => None
  }
}

case class GroupHolder(count: Int, users: Seq[BasicUser], isMore: Boolean)
object GroupHolder {
  implicit val format = (
    (__ \ 'count).format[Int] and
    (__ \ 'users).format[Seq[BasicUser]] and
    (__ \ 'isMore).format[Boolean]
  )(GroupHolder.apply, unlift(GroupHolder.unapply))
}

case class FullLibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  description: Option[String],
  slug: LibrarySlug,
  ownerId: ExternalId[User],
  collaborators: GroupHolder,
  followers: GroupHolder,
  keepCount: Int)

object FullLibraryInfo {
  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'ownerId).format[ExternalId[User]] and
    (__ \ 'collaborators).format[GroupHolder] and
    (__ \ 'followers).format[GroupHolder] and
    (__ \ 'keepCount).format[Int]
  )(FullLibraryInfo.apply, unlift(FullLibraryInfo.unapply))
}

case class LibraryInfoIdKey(libraryId: Id[Library]) extends Key[LibraryInfo] {
  override val version = 1
  val namespace = "library_info_libraryid"
  def toKey(): String = libraryId.id.toString
}

class LibraryInfoIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[LibraryInfoIdKey, LibraryInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
