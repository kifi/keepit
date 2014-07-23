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

  def addLibrary(libAddReq: LibraryAddRequest, ownerId: Id[User]): Either[LibraryFail, FullLibraryInfo] = {
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
            val (collaboratorIds, collaboratorUsers, followerIds, followerUsers, ownerExtId) = db.readOnlyReplica { implicit s =>
              val collabs = libAddReq.collaborators.map { x =>
                val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
                inviteeIdOpt.get
              }
              val follows = libAddReq.followers.map { x =>
                val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
                inviteeIdOpt.get
              }
              val collabBasicUsers = basicUserRepo.loadAll(collabs.toSet).values.toSeq
              val followBasicUsers = basicUserRepo.loadAll(follows.toSet).values.toSeq
              (collabs, collabBasicUsers, follows, followBasicUsers, userRepo.get(ownerId).externalId)
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

            val groupCollabs = GroupHolder(count = collaboratorIds.length, users = collaboratorUsers, isMore = false)
            val groupFollowers = GroupHolder(count = followerIds.length, users = followerUsers, isMore = false)
            Right(FullLibraryInfo(id = Library.publicId(library.id.get), ownerId = ownerExtId, name = libAddReq.name, slug = validSlug,
              visibility = validVisibility, description = libAddReq.description, keepCount = 0,
              collaborators = groupCollabs, followers = groupFollowers))
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

  def getLibraryById(id: Id[Library]): FullLibraryInfo = {
    val (lib, owner, collaborators, followers, numKeeps) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(id)
      val memberships = libraryMembershipRepo.getWithLibraryId(libraryId = lib.id.get)
      val collabIds = for (
        m: LibraryMembership <- {
          memberships.filter { x => x.access == LibraryAccess.READ_WRITE || x.access == LibraryAccess.READ_INSERT }
        }
      ) yield m.userId
      val followIds = for (
        m: LibraryMembership <- {
          memberships.filter { x => x.access == LibraryAccess.READ_ONLY }
        }
      ) yield m.userId

      val collabUsers = basicUserRepo.loadAll(collabIds.toSet).values.toSeq
      val followUsers = basicUserRepo.loadAll(followIds.toSet).values.toSeq

      val owner = basicUserRepo.load(lib.ownerId)
      val numKeeps = 0 //keepRepo.getByLibraryId
      (lib, owner, collabUsers, followUsers, numKeeps)
    }
    val groupCollabs = GroupHolder(count = collaborators.length, users = collaborators, isMore = false)
    val groupFollows = GroupHolder(count = followers.length, users = followers, isMore = false)

    FullLibraryInfo(id = Library.publicId(lib.id.get), name = lib.name, description = lib.description, visibility = lib.visibility, slug = lib.slug,
      ownerId = owner.externalId, collaborators = groupCollabs, followers = groupFollows, keepCount = numKeeps)
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
            val (slug, name) = if (kind == LibraryKind.SYSTEM_MAIN) ("main", "Main Library") else ("secret", "Secret Library")

            val activeLib = libs.head._2.copy(state = LibraryStates.ACTIVE, slug = LibrarySlug(slug), name = name, visibility = LibraryVisibility.SECRET)
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

  def joinLibrary(libraryId: Id[Library], inviteeId: Id[User]): Either[LibraryFail, Library] = {
    db.readWrite { implicit s =>
      libraryInviteRepo.getWithLibraryIdandUserId(libraryId, inviteeId) match {
        case None => Left(LibraryFail("invite not found"))
        case Some(inv) => {
          libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = inviteeId, access = inv.access, showInSearch = true))
          Right(libraryRepo.get(libraryId))
        }
      }
    }
  }

  def declineLibrary(libraryId: Id[Library], inviteeId: Id[User]): Either[LibraryFail, Unit] = {
    db.readWrite { implicit s =>
      libraryInviteRepo.getWithLibraryIdandUserId(libraryId, inviteeId) match {
        case None => Left(LibraryFail("invite not found"))
        case Some(inv) => {
          libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.DECLINED))
          Right()
        }
      }
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
