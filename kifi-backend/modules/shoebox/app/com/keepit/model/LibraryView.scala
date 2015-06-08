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
  whoCanInvite: Option[LibraryInvitePermissions] = None)

@json
case class LibraryModifyRequest(
  name: Option[String] = None,
  slug: Option[String] = None,
  visibility: Option[LibraryVisibility] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  whoCanInvite: Option[LibraryInvitePermissions] = None,
  subscriptions: Option[Seq[LibrarySubscription]] = None)

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

@json
case class LibraryCardInfo(
  id: PublicId[Library],
  name: String,
  description: Option[String],
  color: Option[LibraryColor], // system libraries have no color
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
  following: Option[Boolean], // @deprecated use membership object instead!
  listed: Option[Boolean] = None, // @deprecated use membership object instead! (should this library show up on owner's profile?)
  membership: Option[LibraryMembershipInfo],
  caption: Option[String] = None, // currently only for marketing page
  modifiedAt: DateTime,
  kind: LibraryKind,
  invite: Option[LibraryInviteInfo] = None) // currently only for Invited tab on viewer's own user profile

object LibraryCardInfo {
  def chooseCollaborators(collaborators: Seq[BasicUser]): Seq[BasicUser] = {
    collaborators.sortBy(_.pictureName == "0.jpg").take(3) // owner + up to 3 collaborators shown
  }

  def chooseFollowers(followers: Seq[BasicUser]): Seq[BasicUser] = {
    followers.filter(_.pictureName != "0.jpg").take(4) // 3 shown, 1 extra in case viewer is one and leaves
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
  whoCanInvite: LibraryInvitePermissions,
  modifiedAt: DateTime)

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
