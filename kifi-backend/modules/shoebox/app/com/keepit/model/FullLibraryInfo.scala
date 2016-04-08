package com.keepit.model

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.json
import com.keepit.common.mail.BasicContact
import com.keepit.discussion.Message
import com.keepit.shoebox.data.keep.KeepInfo
import com.keepit.slack.LibrarySlackInfo
import com.keepit.slack.models.{ SlackIntegrationStatus, SlackChannelToLibrary, LibraryToSlackChannel, SlackChannelName }
import com.keepit.social.BasicUser
import com.keepit.social.twitter.TwitterHandle
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.Results.Status
import play.api.http.Status._

import scala.concurrent.Future

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

case class LibraryFail(status: Int, message: String) extends Exception(message) {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}
object LibraryFail {
  val INVALID_LIBRARY_ID = LibraryFail(BAD_REQUEST, "invalid_library_id")
  val INVALID_KEEP_ID = LibraryFail(BAD_REQUEST, "invalid_keep_id")
  val INSUFFICIENT_PERMISSIONS = LibraryFail(FORBIDDEN, "insufficient_permissions")
}

case class ExternalLibraryInitialValues(
  name: String,
  visibility: LibraryVisibility,
  slug: Option[String],
  kind: Option[LibraryKind] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  whoCanInvite: Option[LibraryInvitePermissions] = None,
  space: Option[ExternalLibrarySpace] = None,
  orgMemberAccess: Option[LibraryAccess] = None)

case class LibraryInitialValues(
    name: String,
    visibility: LibraryVisibility,
    slug: Option[String] = None,
    kind: Option[LibraryKind] = None,
    description: Option[String] = None,
    color: Option[LibraryColor] = None,
    listed: Option[Boolean] = None,
    whoCanInvite: Option[LibraryInvitePermissions] = None,
    space: Option[LibrarySpace] = None,
    orgMemberAccess: Option[LibraryAccess] = None) {
  def asLibraryModifications: LibraryModifications = LibraryModifications(
    name = Some(name),
    visibility = Some(visibility),
    slug = slug,
    description = description,
    color = color,
    listed = listed,
    whoCanInvite = whoCanInvite,
    space = space,
    orgMemberAccess = orgMemberAccess
  )
}

object ExternalLibraryInitialValues {
  val readsMobileV1: Reads[ExternalLibraryInitialValues] = (
    (__ \ 'name).read[String] and
    (__ \ 'visibility).read[LibraryVisibility] and
    (__ \ 'slug).readNullable[String] and
    (__ \ 'kind).readNullable[LibraryKind] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'color).readNullable[LibraryColor] and
    (__ \ 'listed).readNullable[Boolean] and
    (__ \ 'whoCanInvite).readNullable[LibraryInvitePermissions] and
    (__ \ 'space).readNullable[ExternalLibrarySpace] and
    (__ \ 'orgMemberAccess).readNullable[LibraryAccess]
  )(ExternalLibraryInitialValues.apply _)
  val reads = readsMobileV1
}

object LibraryInitialValues {
  def forOrgGeneralLibrary(org: Organization): LibraryInitialValues = {
    LibraryInitialValues(
      name = "General",
      description = Some("This library is for keeps the entire team should know about.  All team members are in this library."),
      visibility = LibraryVisibility.ORGANIZATION,
      slug = Some("general"),
      kind = Some(LibraryKind.SYSTEM_ORG_GENERAL),
      space = Some(LibrarySpace.fromOrganizationId(org.id.get)),
      orgMemberAccess = Some(LibraryAccess.READ_WRITE)
    )
  }
}

case class ExternalLibraryModifications(
  name: Option[String] = None,
  slug: Option[String] = None,
  visibility: Option[LibraryVisibility] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  whoCanInvite: Option[LibraryInvitePermissions] = None,
  externalSpace: Option[ExternalLibrarySpace] = None,
  orgMemberAccess: Option[LibraryAccess] = None,
  whoCanComment: Option[LibraryCommentPermissions] = None)

case class LibraryModifications(
  name: Option[String] = None,
  slug: Option[String] = None,
  visibility: Option[LibraryVisibility] = None,
  description: Option[String] = None,
  color: Option[LibraryColor] = None,
  listed: Option[Boolean] = None,
  whoCanInvite: Option[LibraryInvitePermissions] = None,
  space: Option[LibrarySpace] = None,
  orgMemberAccess: Option[LibraryAccess] = None,
  whoCanComment: Option[LibraryCommentPermissions] = None)
object LibraryModifications {
  val adminReads: Reads[LibraryModifications] = (
    (__ \ 'name).readNullable[String] and
    (__ \ 'slug).readNullable[String] and
    (__ \ 'visibility).readNullable[LibraryVisibility] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'color).readNullable[LibraryColor] and
    (__ \ 'listed).readNullable[Boolean] and
    (__ \ 'whoCanInvite).readNullable[LibraryInvitePermissions] and
    (__ \ 'space).readNullable[LibrarySpace] and
    (__ \ 'orgMemberAccess).readNullable[LibraryAccess] and
    (__ \ 'whoCanComment).readNullable[LibraryCommentPermissions]
  )(LibraryModifications.apply _)
}

object ExternalLibraryModifications {
  val readsMobileV1: Reads[ExternalLibraryModifications] = (
    (__ \ 'name).readNullable[String] and
    (__ \ 'slug).readNullable[String] and
    (__ \ 'visibility).readNullable[LibraryVisibility] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'color).readNullable[LibraryColor] and
    (__ \ 'listed).readNullable[Boolean] and
    (__ \ 'whoCanInvite).readNullable[LibraryInvitePermissions] and
    (__ \ 'space).readNullable[ExternalLibrarySpace] and
    (__ \ 'orgMemberAccess).readNullable[LibraryAccess] and
    (__ \ 'whoCanComment).readNullable[LibraryCommentPermissions]
  )(ExternalLibraryModifications.apply _)

  val reads = readsMobileV1 // this can be reassigned, just don't add any breaking changes to an mobile API in prod
}

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

  def fromLibraryAndOwner(lib: Library, image: Option[LibraryImageInfo], owner: BasicUser, org: Option[Organization], inviter: Option[BasicUser] = None)(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      url = LibraryPathHelper.formatLibraryPath(owner, org.map(_.handle), lib.slug),
      color = lib.color,
      image = image,
      owner = owner,
      numKeeps = lib.keepCount,
      numFollowers = lib.memberCount - 1, // remove owner from count
      kind = lib.kind,
      lastKept = lib.lastKept,
      inviter = inviter
    )
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
  whoCanComment: LibraryCommentPermissions,
  modifiedAt: DateTime,
  path: String,
  org: Option[BasicOrganizationView],
  orgMemberAccess: Option[LibraryAccess],
  membership: Option[LibraryMembershipInfo],
  invite: Option[LibraryInviteInfo],
  permissions: Set[LibraryPermission],
  slack: Option[LibrarySlackInfo])

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
      "whoCanComment" -> o.whoCanComment,
      "modifiedAt" -> o.modifiedAt,
      "path" -> o.path,
      "org" -> o.org,
      "orgMemberAccess" -> o.orgMemberAccess,
      "membership" -> o.membership,
      "invite" -> o.invite,
      "permissions" -> o.permissions,
      "slack" -> o.slack
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

case class TwitterLibrarySourceAttribution(screenName: TwitterHandle) extends LibrarySourceAttribution

object TwitterLibrarySourceAttribution {
  implicit val writes = Json.writes[TwitterLibrarySourceAttribution]
}
