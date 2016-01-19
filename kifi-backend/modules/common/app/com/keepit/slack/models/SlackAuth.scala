package com.keepit.slack.models

import com.keepit.common.crypto.CryptoSupport
import com.keepit.common.mail.EmailAddress
import com.keepit.common.strings.ValidInt
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.util.{ Failure, Try }

case class SlackAuthScope(value: String)
object SlackAuthScope {
  val Identify = SlackAuthScope("identify")
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

  val push: Set[SlackAuthScope] = Set(IncomingWebhook, Commands)
  val ingest: Set[SlackAuthScope] = Set(SearchRead, ReactionsWrite, Commands)

  val pushAnywhere: Set[SlackAuthScope] = Set(ChannelsRead, ChatWriteBot, Commands)
  val ingestAnywhere: Set[SlackAuthScope] = ingest + ChannelsRead
  val teamSetup = pushAnywhere ++ ingestAnywhere + TeamRead

  val userSignup: Set[SlackAuthScope] = Set(UsersRead)
  val userLogin: Set[SlackAuthScope] = Set(Identify)

  val slackReads: Reads[Set[SlackAuthScope]] = Reads { j => j.validate[String].map(s => s.split(",").toSet.map(SlackAuthScope.apply)) }
  val dbFormat: Format[SlackAuthScope] = Format(
    Reads { j => j.validate[String].map(SlackAuthScope.apply) },
    Writes { sas => JsString(sas.value) }
  )
}

@json case class SlackAuthorizationCode(code: String)

case class SlackState(state: String)
object SlackState {
  def apply(value: JsValue): SlackState = SlackState(CryptoSupport.encodeBase64(Json.stringify(value)))
  def toJson(state: SlackState): Try[JsValue] = Try(Json.parse(CryptoSupport.decodeBase64(state.state))).orElse(Failure(SlackAPIFailure.StateError(state)))
}

case class SlackAuthorizationRequest(
  url: String,
  scopes: Set[SlackAuthScope],
  uniqueToken: String,
  redirectUri: Option[String])

case class SlackAuthorizationResponse(
  accessToken: SlackAccessToken,
  scopes: Set[SlackAuthScope],
  teamName: SlackTeamName,
  teamId: SlackTeamId,
  incomingWebhook: Option[SlackIncomingWebhook])
object SlackAuthorizationResponse {
  implicit val reads: Reads[SlackAuthorizationResponse] = (
    (__ \ 'access_token).read[SlackAccessToken] and
    (__ \ 'scope).read[Set[SlackAuthScope]](SlackAuthScope.slackReads) and
    (__ \ 'team_name).read[SlackTeamName] and
    (__ \ 'team_id).read[SlackTeamId] and
    (__ \ 'incoming_webhook).readNullable[SlackIncomingWebhook]
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
  implicit val slackReads: Reads[SlackTeamInfo] = (
    (__ \ 'id).read[SlackTeamId] and
    (__ \ 'name).read[SlackTeamName] and
    (__ \ 'domain).read[SlackTeamDomain] and
    (__ \ 'email_domain).read[String].map(domains => domains.split(',').toList.map(SlackTeamEmailDomain(_))) and
    (__ \ 'icon).read(SlackIconReads)
  )(SlackTeamInfo.apply _)
}

case class SlackUserProfile(
  firstName: Option[String],
  lastName: Option[String],
  fullName: Option[String],
  emailAddress: EmailAddress,
  icon: Map[Int, String],
  originalJson: JsValue)

object SlackUserProfile {
  implicit val reads: Reads[SlackUserProfile] = (
    (__ \ 'first_name).readNullable[String].map(_.filter(_.nonEmpty)) and
    (__ \ 'last_name).readNullable[String].map(_.filter(_.nonEmpty)) and
    (__ \ 'real_name).readNullable[String].map(_.filter(_.nonEmpty)) and
    (__ \ 'email).read[EmailAddress] and
    SlackIconReads and
    Reads(JsSuccess(_))
  )(SlackUserProfile.apply _)
}

case class SlackUserInfo(
  id: SlackUserId,
  name: SlackUsername,
  profile: SlackUserProfile,
  originalJson: JsValue)

object SlackUserInfo {
  private val reads: Reads[SlackUserInfo] = (
    (__ \ 'id).read[SlackUserId] and
    (__ \ 'name).read[SlackUsername] and
    (__ \ 'profile).read[SlackUserProfile] and
    Reads(JsSuccess(_))
  )(SlackUserInfo.apply _)

  private val writes: Writes[SlackUserInfo] = Writes(_.originalJson)

  implicit val format: Format[SlackUserInfo] = Format(reads, writes)
}

object SlackIconReads extends Reads[Map[Int, String]] {
  private val sizePattern = """^image_(\d+)$""".r
  def reads(value: JsValue) = value.validate[JsObject].map { obj =>
    val isDefaultImage = (obj \ "image_default").asOpt[Boolean].getOrElse(false)
    if (isDefaultImage) Map.empty[Int, String] else obj.value.collect { case (sizePattern(ValidInt(size)), JsString(imageUrl)) => size -> imageUrl }.toMap
  }
}

