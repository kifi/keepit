package com.keepit.commanders

import javax.inject.Inject

import akka.actor.Scheduler
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
    scheduler: Scheduler,
    clock: Clock) extends Logging {

  def addLibrary(libInfo: LibraryAddRequest, ownerId: Id[User]) = {

    val (collaboratorIds, collaboratorUsers, followerIds, followerUsers, ownerExtId) = db.readOnlyReplica { implicit s =>
      val collabs = libInfo.collaborators.map { x =>
        val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
        inviteeIdOpt.get
      }
      val follows = libInfo.followers.map { x =>
        val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
        inviteeIdOpt.get
      }
      val collabBasicUsers = basicUserRepo.loadAll(collabs.toSet).values.toSeq // type Seq[BasicUser]
      val followBasicUsers = basicUserRepo.loadAll(follows.toSet).values.toSeq // type Seq[BasicUser]

      (collabs, collabBasicUsers, follows, followBasicUsers, userRepo.get(ownerId).externalId)
    }

    val (libId, libExtId) = db.readWrite { implicit s =>
      val lib = libraryRepo.save(Library(ownerId = ownerId, name = libInfo.name, description = libInfo.description,
        visibility = libInfo.visibility, slug = libInfo.slug))
      val libId = lib.id.get
      val libMem = libraryMembershipRepo.save(LibraryMembership(libraryId = libId, userId = ownerId, access = LibraryAccess.OWNER))
      (libId, lib.externalId)
    }

    val bulkInvites1 = for (c <- collaboratorIds) yield LibraryInvite(libraryId = libId, ownerId = ownerId, userId = c, access = LibraryAccess.READ_WRITE)
    val bulkInvites2 = for (c <- followerIds) yield LibraryInvite(libraryId = libId, ownerId = ownerId, userId = c, access = LibraryAccess.READ_ONLY)

    inviteBulkUsers(bulkInvites1 ++ bulkInvites2)

    //BasicLibraryInfo(id = libExtId, ownerId = ownerExtId, name = libInfo.name, slug = libInfo.slug,
    //  visibility = libInfo.visibility, shortDescription = libInfo.description)

    val groupCollabs = GroupHolder(count = collaboratorIds.length, users = collaboratorUsers, isMore = false)
    val groupFollowers = GroupHolder(count = followerIds.length, users = followerUsers, isMore = false)
    FullLibraryInfo(id = libExtId, ownerId = ownerExtId, name = libInfo.name, slug = libInfo.slug,
      visibility = libInfo.visibility, description = libInfo.description, keepCount = 0,
      collaborators = groupCollabs, followers = groupFollowers)
  }

  private def inviteBulkUsers(invites: Seq[LibraryInvite]) {
    db.readWrite { implicit s =>
      invites.map { invite => libraryInviteRepo.save(invite) }
    }
  }
}

case class LibraryAddRequest(
  name: String,
  visibility: LibraryVisibility,
  description: Option[String] = None,
  slug: LibrarySlug,
  collaborators: Seq[ExternalId[User]],
  followers: Seq[ExternalId[User]])

case class BasicLibrary(
  id: ExternalId[Library],
  name: String,
  visibility: LibraryVisibility,
  shortDescription: Option[String],
  slug: LibrarySlug,
  ownerId: ExternalId[User])
object BasicLibrary {
  implicit val libraryExternalIdFormat = ExternalId.format[Library]

  implicit val format = (
    (__ \ 'id).format[ExternalId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'shortDescription).formatNullable[String] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'ownerId).format[ExternalId[User]]
  )(BasicLibrary.apply, unlift(BasicLibrary.unapply))

  def fromLibraryAndOwner(lib: Library, owner: User): BasicLibrary = {
    BasicLibrary(
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

case class BasicLibraryIdKey(libraryId: Id[Library]) extends Key[BasicLibrary] {
  override val version = 1
  val namespace = "basic_library_libraryid"
  def toKey(): String = libraryId.id.toString
}

class BasicLibraryIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[BasicLibraryIdKey, BasicLibrary](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

