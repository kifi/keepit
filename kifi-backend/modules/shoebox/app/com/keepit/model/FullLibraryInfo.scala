package com.keepit.model

import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.json
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.BasicContact
import com.keepit.common.store.ImagePath
import com.keepit.social.{ BasicUser, BasicUserFields }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

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
case class LibrarySubscriptionKey(name: String, info: SubscriptionInfo)

case class ExternalLibraryCreateRequest(
  name: String,
  visibility: LibraryVisibility,
  slug: Option[String],
  kind: Option[LibraryKind] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  whoCanInvite: Option[LibraryInvitePermissions] = None,
  subscriptions: Option[Seq[LibrarySubscriptionKey]] = None,
  space: Option[ExternalLibrarySpace] = None,
  orgMemberAccess: Option[LibraryAccess] = None)

object ExternalLibraryCreateRequest {
  val readsMobileV1: Reads[ExternalLibraryCreateRequest] = (
    (__ \ 'name).read[String] and
    (__ \ 'visibility).read[LibraryVisibility] and
    (__ \ 'slug).readNullable[String] and
    (__ \ 'kind).readNullable[LibraryKind] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'color).readNullable[LibraryColor] and
    (__ \ 'listed).readNullable[Boolean] and
    (__ \ 'whoCanInvite).readNullable[LibraryInvitePermissions] and
    (__ \ 'subscriptions).readNullable[Seq[LibrarySubscriptionKey]] and
    (__ \ 'space).readNullable[ExternalLibrarySpace] and
    (__ \ 'orgMemberAccess).readNullable[LibraryAccess]
  )(ExternalLibraryCreateRequest.apply _)
  val reads = readsMobileV1
}

case class LibraryCreateRequest(
  name: String,
  visibility: LibraryVisibility,
  slug: String,
  kind: Option[LibraryKind] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  whoCanInvite: Option[LibraryInvitePermissions] = None,
  subscriptions: Option[Seq[LibrarySubscriptionKey]] = None,
  space: Option[LibrarySpace] = None,
  orgMemberAccess: Option[LibraryAccess] = None)

case class ExternalLibraryModifyRequest(
  name: Option[String] = None,
  slug: Option[String] = None,
  visibility: Option[LibraryVisibility] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  whoCanInvite: Option[LibraryInvitePermissions] = None,
  subscriptions: Option[Seq[LibrarySubscriptionKey]] = None,
  externalSpace: Option[ExternalLibrarySpace] = None,
  orgMemberAccess: Option[LibraryAccess] = None)

object ExternalLibraryModifyRequest {
  val readsMobileV1: Reads[ExternalLibraryModifyRequest] = (
    (__ \ 'name).readNullable[String] and
    (__ \ 'slug).readNullable[String] and
    (__ \ 'visibility).readNullable[LibraryVisibility] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'color).readNullable[LibraryColor] and
    (__ \ 'listed).readNullable[Boolean] and
    (__ \ 'whoCanInvite).readNullable[LibraryInvitePermissions] and
    (__ \ 'subscriptions).readNullable[Seq[LibrarySubscriptionKey]] and
    (__ \ 'space).readNullable[ExternalLibrarySpace] and
    (__ \ 'orgMemberAccess).readNullable[LibraryAccess]
  )(ExternalLibraryModifyRequest.apply _)

  val reads = readsMobileV1 // this can be reassigned, just don't add any breaking changes to an mobile API in prod
}

case class LibraryModifyRequest(
  name: Option[String] = None,
  slug: Option[String] = None,
  visibility: Option[LibraryVisibility] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  whoCanInvite: Option[LibraryInvitePermissions] = None,
  subscriptions: Option[Seq[LibrarySubscriptionKey]] = None,
  space: Option[LibrarySpace] = None,
  orgMemberAccess: Option[LibraryAccess] = None)

case class LibraryModifyResponse(
  modifiedLibrary: Library,
  keepChanges: Future[Unit],
  edits: Map[String, Boolean])

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

  def fromLibraryAndOwner(lib: Library, image: Option[LibraryImage], owner: BasicUser, org: Option[Organization], inviter: Option[BasicUser] = None)(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      url = LibraryPathHelper.formatLibraryPath(owner, org.map(_.handle), lib.slug),
      color = lib.color,
      image = image.map(LibraryImageInfo.fromImage),
      owner = owner,
      numKeeps = lib.keepCount,
      numFollowers = lib.memberCount - 1, // remove owner from count
      kind = lib.kind,
      lastKept = lib.lastKept,
      inviter = inviter
    )
  }
}

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
  membership: Option[LibraryMembershipInfo],
  invite: Option[LibraryInviteInfo], // currently only for Invited tab on viewer's own user profile
  caption: Option[String] = None, // currently only for marketing page
  modifiedAt: DateTime,
  kind: LibraryKind,
  path: String,
  org: Option[BasicOrganizationView],
  orgMemberAccess: Option[LibraryAccess])

object LibraryCardInfo {
  implicit val writes = new Writes[LibraryCardInfo] {
    import com.keepit.common.core._
    def writes(o: LibraryCardInfo) = Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "color" -> o.color,
      "image" -> o.image,
      "slug" -> o.slug,
      "visibility" -> o.visibility,
      "owner" -> o.owner,
      "numKeeps" -> o.numKeeps,
      "numFollowers" -> o.numFollowers,
      "followers" -> o.followers,
      "numCollaborators" -> o.numCollaborators,
      "collaborators" -> o.collaborators,
      "lastKept" -> o.lastKept,
      "following" -> o.following,
      "membership" -> o.membership,
      "invite" -> o.invite,
      "caption" -> o.caption,
      "modifiedAt" -> o.modifiedAt,
      "kind" -> o.kind,
      "path" -> o.path,
      "org" -> o.org,
      "orgMemberAccess" -> o.orgMemberAccess).nonNullFields
  }
  def chooseCollaborators(collaborators: Seq[BasicUser]): Seq[BasicUser] = {
    collaborators.sortBy(_.pictureName == "0.jpg").take(3) // owner + up to 3 collaborators shown
  }

  def chooseFollowers(followers: Seq[BasicUser]): Seq[BasicUser] = {
    followers.filter(_.pictureName != "0.jpg").take(4) // 3 shown, 1 extra in case viewer is one and leaves
  }
}

case class MaybeLibraryMember(member: Either[BasicUser, BasicContact], access: Option[LibraryAccess], lastInvitedAt: Option[DateTime])

object MaybeLibraryMember {
  implicit val writes = Writes[MaybeLibraryMember] { member =>
    val identityFields = member.member.fold(user => Json.toJson(user), contact => Json.toJson(contact)).as[JsObject]
    val libraryRelatedFields = Json.obj("membership" -> member.access, "lastInvitedAt" -> member.lastInvitedAt)
    json.aggressiveMinify(identityFields ++ libraryRelatedFields)
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
  modifiedAt: DateTime,
  path: String,
  org: Option[BasicOrganizationView],
  orgMemberAccess: Option[LibraryAccess],
  membership: Option[LibraryMembershipInfo],
  invite: Option[LibraryInviteInfo])

object FullLibraryInfo {
  implicit val sourceWrites = LibrarySourceAttribution.writes
  implicit val writes = new Writes[FullLibraryInfo] {
    import com.keepit.common.core._
    def writes(o: FullLibraryInfo) = Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "visibility" -> o.visibility,
      "description" -> o.description,
      "slug" -> o.slug,
      "url" -> o.url,
      "color" -> o.color,
      "image" -> o.image,
      "kind" -> o.kind,
      "lastKept" -> o.lastKept,
      "owner" -> o.owner,
      "followers" -> o.followers,
      "collaborators" -> o.collaborators,
      "keeps" -> o.keeps,
      "numKeeps" -> o.numKeeps,
      "numCollaborators" -> o.numCollaborators,
      "numFollowers" -> o.numFollowers,
      "attr" -> o.attr,
      "whoCanInvite" -> o.whoCanInvite,
      "modifiedAt" -> o.modifiedAt,
      "path" -> o.path,
      "org" -> o.org,
      "orgMemberAccess" -> o.orgMemberAccess,
      "membership" -> o.membership,
      "invite" -> o.invite
    ).nonNullFields
  }
}

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
