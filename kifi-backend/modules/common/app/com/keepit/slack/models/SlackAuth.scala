package com.keepit.slack.models

import java.util.UUID
import com.keepit.common.cache._
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.strings.ValidInt
import com.keepit.model.User
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class SlackAuthScope(value: String)
object SlackAuthScope {
  val Identify = SlackAuthScope("identify")
  val Bot = SlackAuthScope("bot")
  val Commands = SlackAuthScope("commands")
  val ChannelsWrite = SlackAuthScope("channels:write")
  val ChannelsHistory = SlackAuthScope("channels:history")
  val ChannelsRead = SlackAuthScope("channels:read")
  val ChatWrite = SlackAuthScope("chat:write")
  val ChatWriteBot = SlackAuthScope("chat:write:bot")
  val ChatWriteUser = SlackAuthScope("chat:write:user")
  val EmojiRead = SlackAuthScope("emoji:read")
  val FilesWriteUser = SlackAuthScope("files:write:user")
  val FilesRead = SlackAuthScope("files:read")
  val GroupsWrite = SlackAuthScope("groups:write")
  val GroupsHistory = SlackAuthScope("groups:history")
  val GroupsRead = SlackAuthScope("groups:read")
  val IncomingWebhook = SlackAuthScope("incoming-webhook")
  val ImWrite = SlackAuthScope("im:write")
  val ImHistory = SlackAuthScope("im:history")
  val ImRead = SlackAuthScope("im:read")
  val MpimWrite = SlackAuthScope("mpim:write")
  val MpimHistory = SlackAuthScope("mpim:history")
  val MpimRead = SlackAuthScope("mpim:read")
  val PinsWrite = SlackAuthScope("pins:write")
  val PinsRead = SlackAuthScope("pins:read")
  val ReactionsWrite = SlackAuthScope("reactions:write")
  val ReactionsRead = SlackAuthScope("reactions:read")
  val SearchRead = SlackAuthScope("search:read")
  val StarsWrite = SlackAuthScope("stars:write")
  val StarsRead = SlackAuthScope("stars:read")
  val TeamRead = SlackAuthScope("team:read")
  val UsersRead = SlackAuthScope("users:read")
  val UsersWrite = SlackAuthScope("users:write")

  val brokenPush: Set[SlackAuthScope] = Set(Commands, ChatWriteBot) + ChannelsRead // adding ChannelsReads *temporarily* as an attempt to backfill some of the missing channel ids
  val newPush: Set[SlackAuthScope] = Set(Commands, IncomingWebhook)
  val ingest: Set[SlackAuthScope] = Set(SearchRead, ReactionsWrite, Commands)
  val integrationSetup = newPush

  val pushAnywhere: Set[SlackAuthScope] = Set(ChannelsRead, ChatWriteBot, Commands)
  val ingestAnywhere: Set[SlackAuthScope] = ingest + ChannelsRead
  val teamSetup = Set(TeamRead)
  val syncPublicChannels = teamSetup ++ pushAnywhere ++ ingestAnywhere

  val userSignup: Set[SlackAuthScope] = Set(UsersRead, TeamRead)
  val userLogin: Set[SlackAuthScope] = Set(Identify)

  val slackReads: Reads[Set[SlackAuthScope]] = Reads { j => j.validate[String].map(s => s.split(",").toSet.map(SlackAuthScope.apply)) }
  val dbFormat: Format[SlackAuthScope] = Format(
    Reads { j => j.validate[String].map(SlackAuthScope.apply) },
    Writes { sas => JsString(sas.value) }
  )
}

@json case class SlackAuthorizationCode(code: String)

case class SlackAuthState(state: String)
object SlackAuthState {
  def apply(): SlackAuthState = SlackAuthState(UUID.randomUUID().toString)
}

case class SlackAuthorizationRequest(
  url: String,
  scopes: Set[SlackAuthScope],
  uniqueToken: String,
  redirectUri: Option[String])

case class BotUserAuthorization(
  userId: SlackUserId,
  accessToken: SlackAccessToken)
object BotUserAuthorization {
  implicit val reads: Reads[BotUserAuthorization] = (
    (__ \ 'bot_user_id).read[SlackUserId] and
    (__ \ 'bot_access_token).read[SlackAccessToken]
  )(BotUserAuthorization.apply _)
}

case class SlackAuthorizationResponse(
  accessToken: SlackAccessToken,
  scopes: Set[SlackAuthScope],
  teamName: SlackTeamName,
  teamId: SlackTeamId,
  incomingWebhook: Option[SlackIncomingWebhook],
  botAuth: Option[BotUserAuthorization])
object SlackAuthorizationResponse {
  implicit val reads: Reads[SlackAuthorizationResponse] = (
    (__ \ 'access_token).read[SlackAccessToken] and
    (__ \ 'scope).read[Set[SlackAuthScope]](SlackAuthScope.slackReads) and
    (__ \ 'team_name).read[SlackTeamName] and
    (__ \ 'team_id).read[SlackTeamId] and
    (__ \ 'incoming_webhook).readNullable[SlackIncomingWebhook] and
    (__ \ 'bot).readNullable[BotUserAuthorization]
  )(SlackAuthorizationResponse.apply _)
}

case class SlackIdentifyResponse(
  url: String,
  teamName: SlackTeamName,
  userName: SlackUsername,
  teamId: SlackTeamId,
  userId: SlackUserId)
object SlackIdentifyResponse {
  implicit val reads: Reads[SlackIdentifyResponse] = (
    (__ \ 'url).read[String] and
    (__ \ 'team).read[String].map(SlackTeamName(_)) and
    (__ \ 'user).read[String].map(SlackUsername(_)) and
    (__ \ 'team_id).read[String].map(SlackTeamId(_)) and
    (__ \ 'user_id).read[String].map(SlackUserId(_))
  )(SlackIdentifyResponse.apply _)
}

case class SlackTeamInfo(
  id: SlackTeamId,
  name: SlackTeamName,
  domain: SlackTeamDomain,
  emailDomains: Seq[SlackTeamEmailDomain],
  icon: Map[Int, String])

object SlackTeamInfo {
  private val slackIconReads = new Reads[Map[Int, String]] {
    private val sizePattern = """^image_(\d+)$""".r
    def reads(value: JsValue) = value.validate[JsObject].map { obj =>
      val isDefaultImage = (obj \ "image_default").asOpt[Boolean].getOrElse(false)
      if (isDefaultImage) Map.empty[Int, String] else obj.value.collect { case (sizePattern(ValidInt(size)), JsString(imageUrl)) => size -> imageUrl }.toMap
    }
  }

  implicit val slackReads: Reads[SlackTeamInfo] = (
    (__ \ 'id).read[SlackTeamId] and
    (__ \ 'name).read[SlackTeamName] and
    (__ \ 'domain).read[SlackTeamDomain] and
    (__ \ 'email_domain).read[String].map(domains => domains.split(',').toList.map(_.trim).collect { case domain if domain.nonEmpty => SlackTeamEmailDomain(domain) }) and
    (__ \ 'icon).read(slackIconReads)
  )(SlackTeamInfo.apply _)
}

case class SlackUserProfile(
  firstName: Option[String],
  lastName: Option[String],
  fullName: Option[String],
  emailAddress: EmailAddress,
  avatarUrl: Option[String],
  originalJson: JsValue)

object SlackUserProfile {
  implicit val reads: Reads[SlackUserProfile] = (
    (__ \ 'first_name).readNullable[String].map(_.filter(_.nonEmpty)) and
    (__ \ 'last_name).readNullable[String].map(_.filter(_.nonEmpty)) and
    (__ \ 'real_name).readNullable[String].map(_.filter(_.nonEmpty)) and
    (__ \ 'email).read[EmailAddress] and
    (__ \ "image_original").readNullable[String] and
    Reads(JsSuccess(_))
  )(SlackUserProfile.apply _)
}

case class SlackUserInfo(
  id: SlackUserId,
  name: SlackUsername,
  profile: SlackUserProfile,
  deleted: Boolean,
  originalJson: JsValue)

object SlackUserInfo {
  private val reads: Reads[SlackUserInfo] = (
    (__ \ 'id).read[SlackUserId] and
    (__ \ 'name).read[SlackUsername] and
    (__ \ 'profile).read[SlackUserProfile] and
    (__ \ 'deleted).readNullable[Boolean].map(_.contains(true)) and
    Reads(JsSuccess(_))
  )(SlackUserInfo.apply _)

  private val writes: Writes[SlackUserInfo] = Writes(_.originalJson)

  implicit val format: Format[SlackUserInfo] = Format(reads, writes)
}

case class SlackTeamMembersKey(slackTeamId: SlackTeamId) extends Key[Seq[SlackUserInfo]] {
  override val version = 1
  val namespace = "slack_team_members"
  def toKey(): String = slackTeamId.value
}

class SlackTeamMembersCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SlackTeamMembersKey, Seq[SlackUserInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SlackTeamMembersCountKey(slackTeamId: SlackTeamId) extends Key[Int] {
  override val version = 1
  val namespace = "slack_team_members_count"
  def toKey(): String = slackTeamId.value
}

class SlackTeamMembersCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[SlackTeamMembersCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

