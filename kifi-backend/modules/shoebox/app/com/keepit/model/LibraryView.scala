package com.keepit.model

import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.json
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.BasicContact
import com.keepit.social.{ BasicUser, BasicUserFields }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

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

case class LibraryFail(status: Int, message: String)

@json
case class LibraryAddRequest(
  name: String,
  visibility: LibraryVisibility,
  slug: String,
  kind: Option[LibraryKind] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  collabInvite: Option[LibraryAccess] = None)

@json
case class LibraryModifyRequest(
  name: Option[String] = None,
  slug: Option[String] = None,
  visibility: Option[LibraryVisibility] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  collabInvite: Option[LibraryAccess] = None)

case class LibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  shortDescription: Option[String],
  url: String,
  color: Option[LibraryColor], // system libraries have no color
  image: Option[LibraryImageInfo],
  owner: BasicUser,
  numKeeps: Int,
  numFollowers: Int,
  kind: LibraryKind,
  lastKept: Option[DateTime],
  inviter: Option[BasicUser])

object LibraryInfo {
  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'shortDescription).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'color).formatNullable[LibraryColor] and
    (__ \ 'image).formatNullable[LibraryImageInfo] and
    (__ \ 'owner).format[BasicUser] and
    (__ \ 'numKeeps).format[Int] and
    (__ \ 'numFollowers).format[Int] and
    (__ \ 'kind).format[LibraryKind] and
    (__ \ 'lastKept).formatNullable[DateTime] and
    (__ \ 'inviter).formatNullable[BasicUser]
  )(LibraryInfo.apply, unlift(LibraryInfo.unapply))

  def fromLibraryAndOwner(lib: Library, image: Option[LibraryImage], owner: BasicUser, inviter: Option[BasicUser] = None)(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      url = Library.formatLibraryPath(owner.username, lib.slug),
      color = lib.color,
      image = image.map(LibraryImageInfo.createInfo(_)),
      owner = owner,
      numKeeps = lib.keepCount,
      numFollowers = lib.memberCount - 1, // remove owner from count
      kind = lib.kind,
      lastKept = lib.lastKept,
      inviter = inviter
    )
  }
}

private[model] abstract class BaseLibraryCardInfo(
  id: PublicId[Library],
  name: String,
  description: Option[String],
  color: Option[LibraryColor], // system libraries have no color
  image: Option[LibraryImageInfo],
  slug: LibrarySlug,
  numKeeps: Int,
  numFollowers: Int,
  followers: Seq[BasicUser],
  numCollaborators: Int,
  collaborators: Seq[BasicUser],
  lastKept: DateTime,
  following: Option[Boolean]) // is viewer following this library? Set to None if viewing anonymously or viewing own profile

@json
case class OwnLibraryCardInfo( // when viewing own created libraries
  id: PublicId[Library],
  name: String,
  description: Option[String],
  color: Option[LibraryColor],
  image: Option[LibraryImageInfo],
  slug: LibrarySlug,
  kind: LibraryKind,
  visibility: LibraryVisibility,
  owner: BasicUser,
  numKeeps: Int,
  numFollowers: Int,
  followers: Seq[BasicUser],
  numCollaborators: Int,
  collaborators: Seq[BasicUser],
  lastKept: DateTime,
  following: Option[Boolean] = None,
  listed: Boolean)
    extends BaseLibraryCardInfo(id, name, description, color, image, slug, numKeeps, numFollowers, followers, numCollaborators, collaborators, lastKept, following)

@json
case class LibraryCardInfo(
  id: PublicId[Library],
  name: String,
  description: Option[String],
  color: Option[LibraryColor],
  image: Option[LibraryImageInfo],
  slug: LibrarySlug,
  visibility: LibraryVisibility,
  owner: BasicUser,
  numKeeps: Int,
  numFollowers: Int,
  followers: Seq[BasicUser],
  numCollaborators: Int,
  collaborators: Seq[BasicUser],
  lastKept: DateTime,
  following: Option[Boolean],
  caption: Option[String])
    extends BaseLibraryCardInfo(id, name, description, color, image, slug, numKeeps, numFollowers, followers, numCollaborators, collaborators, lastKept, following)

object LibraryCardInfo {
  val writesWithoutOwner = Writes[LibraryCardInfo] { o => // for case when receiving end already knows the owner
    JsObject((Json.toJson(o).as[JsObject].value - "owner").toSeq)
  }

  def makeMembersShowable(members: Seq[BasicUser], filterBadPics: Boolean): Seq[BasicUser] = {
    if (filterBadPics) {
      members.filter(_.pictureName != "0.jpg").take(3)
    } else {
      val (membersWithPics, membersNoPics) = members.partition(_.pictureName != "0.jpg")
      membersWithPics ++ membersNoPics
    }
  }
}

@json
case class LibraryNotificationInfo(
  id: PublicId[Library],
  name: String,
  slug: LibrarySlug,
  color: Option[LibraryColor],
  image: Option[LibraryImageInfo],
  owner: BasicUser)

object LibraryNotificationInfo {
  def fromLibraryAndOwner(lib: Library, image: Option[LibraryImage], owner: BasicUser)(implicit config: PublicIdConfiguration): LibraryNotificationInfo = {
    LibraryNotificationInfo(Library.publicId(lib.id.get), lib.name, lib.slug, lib.color, image.map(LibraryImageInfo.createInfo(_)), owner)
  }
}

case class MaybeLibraryMember(member: Either[BasicUser, BasicContact], access: Option[LibraryAccess], lastInvitedAt: Option[DateTime])

object MaybeLibraryMember {
  implicit val writes = Writes[MaybeLibraryMember] { member =>
    val identityFields = member.member.fold(user => Json.toJson(user), contact => Json.toJson(contact)).as[JsObject]
    val libraryRelatedFields = Json.obj("membership" -> member.access, "lastInvitedAt" -> member.lastInvitedAt)
    json.minify(identityFields ++ libraryRelatedFields)
  }
}

case class FullLibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  description: Option[String],
  slug: LibrarySlug,
  url: String,
  color: Option[LibraryColor], // system libraries have no color
  image: Option[LibraryImageInfo],
  kind: LibraryKind,
  lastKept: Option[DateTime],
  owner: BasicUser,
  followers: Seq[BasicUser],
  collaborators: Seq[BasicUser],
  keeps: Seq[KeepInfo],
  numKeeps: Int,
  numCollaborators: Int,
  numFollowers: Int,
  attr: Option[LibrarySourceAttribution] = None,
  collabInvite: Option[LibraryAccess])

object FullLibraryInfo {
  implicit val sourceWrites = LibrarySourceAttribution.writes
  implicit val writes = Json.writes[FullLibraryInfo]
}

case class LibraryInfoIdKey(libraryId: Id[Library]) extends Key[LibraryInfo] {
  override val version = 2
  val namespace = "library_info_libraryid"
  def toKey(): String = libraryId.id.toString
}

class LibraryInfoIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[LibraryInfoIdKey, LibraryInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

sealed trait LibrarySourceAttribution

object LibrarySourceAttribution {
  implicit val writes = new Writes[LibrarySourceAttribution] {
    def writes(x: LibrarySourceAttribution): JsValue = {
      x match {
        case twitter: TwitterLibrarySourceAttribution => Json.obj("twitter" -> TwitterLibrarySourceAttribution.writes.writes(twitter))
      }
    }
  }
}

case class TwitterLibrarySourceAttribution(screenName: String) extends LibrarySourceAttribution

object TwitterLibrarySourceAttribution {
  implicit val writes = Json.writes[TwitterLibrarySourceAttribution]
}
