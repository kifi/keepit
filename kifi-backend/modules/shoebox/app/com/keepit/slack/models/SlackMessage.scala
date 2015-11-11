package com.keepit.slack.models

import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.strings.StringWithReplacements
import com.kifi.macros.json
import play.api.http.Status._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.mvc.Results.Status

case class SlackUserId(value: String)
case class SlackUsername(value: String)

case class SlackTeamId(value: String)
case class SlackTeamName(value: String)

case class SlackChannelId(value: String) // broad sense, can be channel, group or DM
case class SlackChannelName(value: String) // broad sense, can be channel, group or DM

@json
case class SlackAccessToken(token: String)

case class SlackIncomingWebhook(
  channel: SlackChannelName,
  url: String,
  configUrl: String)
object SlackIncomingWebhook {
  implicit val reads: Reads[SlackIncomingWebhook] = (
    (__ \ 'channel).read[String].map(SlackChannelName(_)) and
    (__ \ 'url).read[String] and
    (__ \ 'configuration_url).read[String]
  )(SlackIncomingWebhook.apply _)
}

object SlackDbColumnTypes {
  def userId(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackUserId, String](_.value, SlackUserId(_))
  }
  def username(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackUsername, String](_.value, SlackUsername(_))
  }
  def teamId(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackTeamId, String](_.value, SlackTeamId(_))
  }
  def teamName(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackTeamName, String](_.value, SlackTeamName(_))
  }
  def channelId(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackChannelId, String](_.value, SlackChannelId(_))
  }
  def channel(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackChannelName, String](_.value, SlackChannelName(_))
  }
}

@json
case class SlackAttachment(fallback: String, text: String)

case class SlackMessage( // https://api.slack.com/incoming-webhooks
  text: String,
  channel: Option[String] = None,
  username: String = "Kifi",
  iconUrl: String = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png",
  attachments: Seq[SlackAttachment] = Seq.empty)

object SlackMessage {
  implicit val writes: Writes[SlackMessage] = Writes { o =>
    Json.obj(
      "text" -> o.text,
      "channel" -> o.channel,
      "username" -> o.username,
      "icon_url" -> o.iconUrl,
      "attachments" -> o.attachments
    )
  }

  def escapeSegment(segment: String): String = segment.replaceAllLiterally("<" -> "&lt;", ">" -> "&gt;", "&" -> "&amp")
}

case class SlackAPIFailure(status: Int, error: String, payload: JsValue) extends Exception(s"$status response: $error ($payload)") {
  def asResponse = Status(status)(Json.toJson(this)(SlackAPIFailure.format))
}
object SlackAPIFailure {
  implicit val format: Format[SlackAPIFailure] = Json.format[SlackAPIFailure]

  object Error {
    val generic = "api_error"
    val parse = "unparseable_payload"
    val state = "broken_state"
  }
  def Generic(status: Int, payload: JsValue) = SlackAPIFailure(status, Error.generic, payload)
  def ParseError(payload: JsValue) = SlackAPIFailure(OK, Error.parse, payload)
  def StateError(state: String) = SlackAPIFailure(OK, Error.state, JsString(state))
}

case class SlackAuthScope(value: String)
object SlackAuthScope {
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

  val library: Set[SlackAuthScope] = Set(IncomingWebhook, SearchRead)
  val slackReads: Reads[Set[SlackAuthScope]] = Reads { j => j.validate[String].map(s => s.split(",").toSet.map(SlackAuthScope.apply)) }

  val dbFormat: Format[SlackAuthScope] = Format(
    Reads { j => j.validate[String].map(SlackAuthScope.apply) },
    Writes { sas => JsString(sas.value) }
  )
}

@json
case class SlackAuthorizationCode(code: String)

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
    (__ \ 'team_name).read[String].map(SlackTeamName(_)) and
    (__ \ 'team_id).read[String].map(SlackTeamId(_)) and
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

case class SlackSearchQuery(queryString: String)

case class SlackSearchResponse(query: String, messages: JsObject)
object SlackSearchResponse {
  implicit val reads = Json.reads[SlackSearchResponse]
}
