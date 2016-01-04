package com.keepit.slack.models

import com.keepit.common.crypto.CryptoSupport
import com.keepit.common.strings.ValidInt
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.kifi.macros.json

import scala.util.{ Failure, Try }

case class SlackAuthScope(value: String)
object SlackAuthScope {
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
  emailDomain: Option[SlackTeamEmailDomain],
  icon: Map[Int, String])

object SlackTeamInfo {
  private val iconReads: Reads[Map[Int, String]] = {
    val sizePattern = """^image_(\d+)$""".r
    Reads(_.validate[JsObject].map { obj =>
      val isDefaultImage = (obj \ "image_default").asOpt[Boolean].getOrElse(false)
      if (isDefaultImage) Map.empty[Int, String] else obj.value.collect { case (sizePattern(ValidInt(size)), JsString(imageUrl)) => size -> imageUrl }.toMap
    })
  }
  implicit val slackReads: Reads[SlackTeamInfo] = (
    (__ \ 'id).read[SlackTeamId] and
    (__ \ 'name).read[SlackTeamName] and
    (__ \ 'domain).read[SlackTeamDomain] and
    (__ \ 'email_domain).read[SlackTeamEmailDomain].map(domain => Some(domain).filter(_.value.nonEmpty)) and
    iconReads
  )(SlackTeamInfo.apply _)
}
