package com.keepit.model

import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.path.Path
import com.keepit.slack.models.SlackChannelName
import com.keepit.social.BasicUser
import com.kifi.macros.{ jsonstrict, json }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

@jsonstrict case class BasicSlackChannel(slackChannelName: SlackChannelName)
@jsonstrict case class LiteLibrarySlackInfo(toSlackChannels: Seq[BasicSlackChannel]) // everything needed for slack UI on libraries

case class LiteLibrarySlackInfoKey(libraryId: Id[Library]) extends Key[LiteLibrarySlackInfo] {
  override val version = 1
  val namespace = "lite_library_slack_info_by_library"
  def toKey(): String = libraryId.id.toString
}

class LiteLibrarySlackInfoCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[LiteLibrarySlackInfoKey, LiteLibrarySlackInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

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
  permissions: Set[LibraryPermission],
  caption: Option[String] = None, // currently only for marketing page
  modifiedAt: DateTime,
  kind: LibraryKind,
  path: Path,
  org: Option[BasicOrganizationView],
  orgMemberAccess: Option[LibraryAccess],
  whoCanComment: LibraryCommentPermissions,
  slack: Option[LiteLibrarySlackInfo])

object LibraryCardInfo {
  // This nested-tupley-goodness is because of the 22-field limit on tuples in Scala
  private type LibraryCardInfoFirstArguments = (PublicId[Library], String, Option[String], Option[LibraryColor], Option[LibraryImageInfo], LibrarySlug, LibraryVisibility, BasicUser, Int, Int, Seq[BasicUser], Int, Seq[BasicUser])
  private type LibraryCardInfoSecondArguments = (DateTime, Option[Boolean], Option[LibraryMembershipInfo], Option[LibraryInviteInfo], Set[LibraryPermission], Option[String], DateTime, LibraryKind, Path, Option[BasicOrganizationView], Option[LibraryAccess], LibraryCommentPermissions, Option[LiteLibrarySlackInfo])
  private def fromSadnessTuples(firsts: LibraryCardInfoFirstArguments, seconds: LibraryCardInfoSecondArguments): LibraryCardInfo = (firsts, seconds) match {
    case (
      (id, name, description, color, image, slug, visibility, owner, numKeeps, numFollowers, followers, numCollaborators, collaborators),
      (lastKept, following, membership, invite, permissions, caption, modifiedAt, kind, path, org, orgMemberAccess, whoCanComment, slack)
      ) =>
      LibraryCardInfo(id, name, description, color, image, slug, visibility, owner, numKeeps, numFollowers, followers, numCollaborators, collaborators,
        lastKept, following, membership, invite, permissions, caption, modifiedAt, kind, path, org, orgMemberAccess, whoCanComment, slack)
  }
  private def toSadnessTuples(lci: LibraryCardInfo): (LibraryCardInfoFirstArguments, LibraryCardInfoSecondArguments) = {
    ((lci.id, lci.name, lci.description, lci.color, lci.image, lci.slug, lci.visibility, lci.owner, lci.numKeeps, lci.numFollowers, lci.followers, lci.numCollaborators, lci.collaborators),
      (lci.lastKept, lci.following, lci.membership, lci.invite, lci.permissions, lci.caption, lci.modifiedAt, lci.kind, lci.path, lci.org, lci.orgMemberAccess, lci.whoCanComment, lci.slack))
  }

  val internalFormat: Format[LibraryCardInfo] = {
    val formatFirst = (
      (__ \ 'id).format[PublicId[Library]] and
      (__ \ 'name).format[String] and
      (__ \ 'description).formatNullable[String] and
      (__ \ 'color).formatNullable[LibraryColor] and
      (__ \ 'image).formatNullable[LibraryImageInfo] and
      (__ \ 'slug).format[LibrarySlug] and
      (__ \ 'visibility).format[LibraryVisibility] and
      (__ \ 'owner).format[BasicUser] and
      (__ \ 'numKeeps).format[Int] and
      (__ \ 'numFollowers).format[Int] and
      (__ \ 'followers).format[Seq[BasicUser]] and
      (__ \ 'numCollaborators).format[Int] and
      (__ \ 'collaborators).format[Seq[BasicUser]]
    ).tupled
    val formatSecond = (
      (__ \ 'lastKept).format[DateTime] and
      (__ \ 'following).formatNullable[Boolean] and
      (__ \ 'membership).formatNullable[LibraryMembershipInfo] and
      (__ \ 'invite).formatNullable[LibraryInviteInfo] and
      (__ \ 'permissions).format[Set[LibraryPermission]] and
      (__ \ 'caption).formatNullable[String] and
      (__ \ 'modifiedAt).format[DateTime] and
      (__ \ 'kind).format[LibraryKind] and
      (__ \ 'path).format[Path] and
      (__ \ 'org).formatNullable[BasicOrganizationView] and
      (__ \ 'orgMemberAccess).formatNullable[LibraryAccess] and
      (__ \ 'whoCanComment).format[LibraryCommentPermissions] and
      (__ \ 'slack).formatNullable[LiteLibrarySlackInfo]
    ).tupled
    (formatFirst and formatSecond)(fromSadnessTuples, toSadnessTuples)
  }

  implicit val writes: Writes[LibraryCardInfo] = Writes(internalFormat.writes)
  val internalReads: Reads[LibraryCardInfo] = Reads(internalFormat.reads)

  def chooseCollaborators(collaborators: Seq[BasicUser]): Seq[BasicUser] = {
    collaborators.sortBy(_.pictureName == "0.jpg").take(3) // owner + up to 3 collaborators shown
  }

  def chooseFollowers(followers: Seq[BasicUser]): Seq[BasicUser] = {
    followers.filter(_.pictureName != "0.jpg").take(4) // 3 shown, 1 extra in case viewer is one and leaves
  }
}

