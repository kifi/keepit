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

  def addLibrary(libInfo: LibraryAddRequest, ownerId: Id[User]): Either[LibraryFail, FullLibraryInfo] = {
    val badMessage: Option[String] = {
      if (!libInfo.collaborators.intersect(libInfo.followers).isEmpty) { Some("collaborators & followers overlap!") }
      else if (libInfo.name.isEmpty || !Library.isValidName(libInfo.name)) { Some("invalid library name") }
      else if (libInfo.slug.isEmpty || !LibrarySlug.isValidSlug(libInfo.slug)) { Some("invalid library slug") }
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
        val validVisibility = libInfo.visibility
        val validSlug = LibrarySlug(libInfo.slug)

        val library = db.readWrite { implicit s =>
          val lib = libraryRepo.save(Library(ownerId = ownerId, name = libInfo.name, description = libInfo.description,
            visibility = validVisibility, slug = validSlug, kind = LibraryKind.USER_CREATED))
          val libId = lib.id.get
          libraryMembershipRepo.save(LibraryMembership(libraryId = libId, userId = ownerId, access = LibraryAccess.OWNER))
          lib
        }

        val bulkInvites1 = for (c <- collaboratorIds) yield LibraryInvite(libraryId = library.id.get, ownerId = ownerId, userId = c, access = LibraryAccess.READ_WRITE)
        val bulkInvites2 = for (c <- followerIds) yield LibraryInvite(libraryId = library.id.get, ownerId = ownerId, userId = c, access = LibraryAccess.READ_ONLY)

        inviteBulkUsers(bulkInvites1 ++ bulkInvites2)

        val groupCollabs = GroupHolder(count = collaboratorIds.length, users = collaboratorUsers, isMore = false)
        val groupFollowers = GroupHolder(count = followerIds.length, users = followerUsers, isMore = false)
        Right(FullLibraryInfo(id = Library.publicId(library.id.get).get, ownerId = ownerExtId, name = libInfo.name, slug = validSlug,
          visibility = validVisibility, description = libInfo.description, keepCount = 0,
          collaborators = groupCollabs, followers = groupFollowers))
      }
    }
  }

  def modifyLibrary(libraryId: PublicId[Library], userId: ExternalId[User],
    name: Option[String] = None,
    description: Option[String] = None,
    slug: Option[String] = None,
    visibility: Option[LibraryVisibility] = None): Either[LibraryFail, LibraryInfo] = {
    val idTry = Library.publicId(libraryId)
    idTry match {
      case Failure(ex) => Left(LibraryFail("Invalid Id"))
      case Success(id) => {
        db.readWrite { implicit s =>
          val targetLib = libraryRepo.get(id)
          val targetUser = userRepo.get(userId)
          val membership = libraryMembershipRepo.getWithLibraryIdandUserId(libraryId = targetLib.id.get, userId = targetUser.id.get)
          membership match {
            case None => Left(LibraryFail("Membership not found!"))
            case Some(x) if x.access != LibraryAccess.OWNER => Left(LibraryFail("Member not owner!"))
            case _ => {

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
                val lib = libraryRepo.save(targetLib.copy(name = newName, slug = LibrarySlug(newSlug), visibility = newVisibility, description = newDescription))
                val ownerExtId = basicUserRepo.load(lib.ownerId).externalId
                LibraryInfo(id = Library.publicId(lib.id.get).get, name = lib.name, slug = lib.slug, visibility = lib.visibility,
                  shortDescription = LibraryInfo.descriptionShortener(lib.description), ownerId = ownerExtId)
              }
            }
          }
        }
      }
    }

  }

  private def inviteBulkUsers(invites: Seq[LibraryInvite]) {
    db.readWrite { implicit s =>
      invites.map { invite => libraryInviteRepo.save(invite) }
    }
  }

  def internSystemGeneratedLibraries(userId: Id[User]): Boolean = { // returns true if created, false if already existed
    db.readWrite { implicit session =>

    }
    true
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
      id = Library.publicId(lib.id.get).get,
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
