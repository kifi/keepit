package com.keepit.model

import com.keepit.commanders.KeepInfo
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.json
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.BasicContact
import com.keepit.social.BasicUser
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

@json case class LibraryAddRequest(name: String,
  visibility: LibraryVisibility,
  description: Option[String] = None,
  slug: String,
  color: Option[HexColor] = None)

case class LibraryInfo(id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  shortDescription: Option[String],
  url: String,
  color: Option[HexColor] = None,
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
    (__ \ 'color).formatNullable[HexColor] and
    (__ \ 'owner).format[BasicUser] and
    (__ \ 'numKeeps).format[Int] and
    (__ \ 'numFollowers).format[Int] and
    (__ \ 'kind).format[LibraryKind] and
    (__ \ 'lastKept).formatNullable[DateTime] and
    (__ \ 'inviter).formatNullable[BasicUser]
  )(LibraryInfo.apply, unlift(LibraryInfo.unapply))

  def fromLibraryAndOwner(lib: Library, owner: BasicUser, keepCount: Int, inviter: Option[BasicUser])(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      url = Library.formatLibraryPath(owner.username, lib.slug),
      color = lib.color,
      owner = owner,
      numKeeps = keepCount,
      numFollowers = lib.memberCount - 1, // remove owner from count
      kind = lib.kind,
      lastKept = lib.lastKept,
      inviter = inviter
    )
  }
}

@json
case class LibraryCardInfo(id: PublicId[Library],
  name: String,
  description: Option[String],
  color: Option[HexColor],
  image: Option[LibraryImageInfo],
  slug: LibrarySlug,
  owner: BasicUser,
  numKeeps: Int,
  numFollowers: Int,
  followers: Seq[BasicUser],
  caption: Option[String])

object LibraryCardInfo {
  val writesWithoutOwner = Writes[LibraryCardInfo] { o => // for case when receiving end already knows the owner
    JsObject((Json.toJson(o).asInstanceOf[JsObject].value - "owner").toSeq)
  }

  def showable(followers: Seq[BasicUser], isAuthenticatedRequest: Boolean): Seq[BasicUser] = {
    if (isAuthenticatedRequest) {
      val goodLooking = followers.filter(_.pictureName != "0.jpg")
      if (goodLooking.size <= 7) goodLooking else goodLooking.take(3) // only 7 can fit
    } else {
      Seq.empty
    }
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

case class FullLibraryInfo(id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  description: Option[String],
  slug: LibrarySlug,
  url: String,
  color: Option[HexColor] = None,
  image: Option[LibraryImageInfo] = None,
  kind: LibraryKind,
  lastKept: Option[DateTime],
  owner: BasicUser,
  followers: Seq[BasicUser],
  keeps: Seq[KeepInfo],
  numKeeps: Int,
  numCollaborators: Int,
  numFollowers: Int)

object FullLibraryInfo {
  implicit val writes = Json.writes[FullLibraryInfo]
}

case class LibraryInfoIdKey(libraryId: Id[Library]) extends Key[LibraryInfo] {
  override val version = 2
  val namespace = "library_info_libraryid"
  def toKey(): String = libraryId.id.toString
}

class LibraryInfoIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[LibraryInfoIdKey, LibraryInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
