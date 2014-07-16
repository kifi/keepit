package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.Clock
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.logging.{ AccessLog, Logging }

import scala.concurrent.duration.Duration

class LibraryCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    clock: Clock) extends Logging {

  def addLibrary(libInfo: LibraryAddRequest, ownerId: Id[User]): Either[LibraryFail, FullLibraryInfo] = {
    val badMessage: Option[String] = {
      if (!libInfo.collaborators.intersect(libInfo.followers).isEmpty) { Some("collaborators & followers overlap!") }
      else if (!Library.isValidName(libInfo.name)) { Some("invalid library name") }
      else if (!LibrarySlug.isValidSlug(libInfo.slug)) { Some("invalid library slug") }
      else { None }
    }
    badMessage match {
      case Some(x) => Left(LibraryFail(x))
      case _ => {
        val (collaboratorIds, collaboratorUsers, followerIds, followerUsers, ownerExtId) = db.readOnlyReplica { implicit s =>
          val collabs = libInfo.collaborators.map { x =>
            val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
            inviteeIdOpt.get
          }
          val follows = libInfo.followers.map { x =>
            val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
            inviteeIdOpt.get
          }
          val collabBasicUsers = basicUserRepo.loadAll(collabs.toSet).values.toSeq
          val followBasicUsers = basicUserRepo.loadAll(follows.toSet).values.toSeq

          (collabs, collabBasicUsers, follows, followBasicUsers, userRepo.get(ownerId).externalId)
        }
        val validVisibility = LibraryVisibility(libInfo.visibility)
        val validSlug = LibrarySlug(libInfo.slug)

        val (libId, libExtId) = db.readWrite { implicit s =>
          val lib = libraryRepo.save(Library(ownerId = ownerId, name = libInfo.name, description = libInfo.description,
            visibility = validVisibility, slug = validSlug))
          val libId = lib.id.get
          libraryMembershipRepo.save(LibraryMembership(libraryId = libId, userId = ownerId, access = LibraryAccess.OWNER))
          (libId, lib.externalId)
        }

        val bulkInvites1 = for (c <- collaboratorIds) yield LibraryInvite(libraryId = libId, ownerId = ownerId, userId = c, access = LibraryAccess.READ_WRITE)
        val bulkInvites2 = for (c <- followerIds) yield LibraryInvite(libraryId = libId, ownerId = ownerId, userId = c, access = LibraryAccess.READ_ONLY)

        inviteBulkUsers(bulkInvites1 ++ bulkInvites2)

        val groupCollabs = GroupHolder(count = collaboratorIds.length, users = collaboratorUsers, isMore = false)
        val groupFollowers = GroupHolder(count = followerIds.length, users = followerUsers, isMore = false)
        Right(FullLibraryInfo(id = libExtId, ownerId = ownerExtId, name = libInfo.name, slug = validSlug,
          visibility = validVisibility, description = libInfo.description, keepCount = 0,
          collaborators = groupCollabs, followers = groupFollowers))
      }
    }
  }

  private def inviteBulkUsers(invites: Seq[LibraryInvite]) {
    db.readWrite { implicit s =>
      invites.map { invite => libraryInviteRepo.save(invite) }
    }
  }
}

case class LibraryFail(message: String) extends AnyVal

case class LibraryAddRequest(
  name: String,
  visibility: String,
  description: Option[String] = None,
  slug: String,
  collaborators: Seq[ExternalId[User]],
  followers: Seq[ExternalId[User]])

case class LibraryInfo(
  id: ExternalId[Library],
  name: String,
  visibility: LibraryVisibility,
  shortDescription: Option[String],
  slug: LibrarySlug,
  ownerId: ExternalId[User])
object LibraryInfo {
  implicit val libraryExternalIdFormat = ExternalId.format[Library]

  implicit val format = (
    (__ \ 'id).format[ExternalId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'shortDescription).formatNullable[String] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'ownerId).format[ExternalId[User]]
  )(LibraryInfo.apply, unlift(LibraryInfo.unapply))

  def fromLibraryAndOwner(lib: Library, owner: User): LibraryInfo = {
    LibraryInfo(
      id = lib.externalId,
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      slug = lib.slug,
      ownerId = owner.externalId
    )
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
  id: ExternalId[Library],
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
    (__ \ 'id).format[ExternalId[Library]] and
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

