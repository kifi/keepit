package com.keepit.slack

import com.keepit.common.strings.StringWithReplacements
import com.kifi.macros.json
import play.api.http.Status._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.mvc.Results.Status

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

case class SlackAPIFail(status: Int, error: String, payload: JsValue) extends Exception(s"$status response: $error ($payload)") {
  def asResponse = Status(status)(Json.toJson(this)(SlackAPIFail.format))
}
object SlackAPIFail {
  implicit val format: Format[SlackAPIFail] = Json.format[SlackAPIFail]

  object Error {
    val generic = "api_error"
    val parse = "unparseable_payload"
    val state = "broken_state"
  }
  def Generic(status: Int, payload: JsValue) = SlackAPIFail(status, Error.generic, payload)
  def ParseError(payload: JsValue) = SlackAPIFail(OK, Error.parse, payload)
  def StateError(state: String) = SlackAPIFail(OK, Error.state, JsString(state))
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

  val library: Set[SlackAuthScope] = Set(IncomingWebhook)
  val slackReads: Reads[Set[SlackAuthScope]] = Reads { j => j.validate[String].map(s => s.split(",").toSet.map(SlackAuthScope.apply)) }

  val dbFormat: Format[SlackAuthScope] = Format(
    Reads { j => j.validate[String].map(SlackAuthScope.apply) },
    Writes { sas => JsString(sas.value) }
  )
}

case class SlackAuthorizationRequest(
  url: String,
  scopes: Set[SlackAuthScope],
  uniqueToken: String,
  redirectUri: Option[String])

@json
case class SlackAuthorizationCode(code: String)
@json
case class SlackAccessToken(token: String)

case class SlackIncomingWebhook(
  url: String,
  channel: String,
  configUrl: String)
object SlackIncomingWebhook {
  implicit val reads: Reads[SlackIncomingWebhook] = (
    (__ \ 'url).read[String] and
    (__ \ 'channel).read[String] and
    (__ \ 'configuration_url).read[String]
  )(SlackIncomingWebhook.apply _)
}

case class SlackAuthorizationResponse(
  accessToken: SlackAccessToken,
  scopes: Set[SlackAuthScope],
  teamName: String,
  teamId: String,
  incomingWebhook: Option[SlackIncomingWebhook])
object SlackAuthorizationResponse {
  implicit val reads: Reads[SlackAuthorizationResponse] = (
    (__ \ 'access_token).read[SlackAccessToken] and
    (__ \ 'scope).read[Set[SlackAuthScope]](SlackAuthScope.slackReads) and
    (__ \ 'team_name).read[String] and
    (__ \ 'team_id).read[String] and
    (__ \ 'incoming_webhook).readNullable[SlackIncomingWebhook]
  )(SlackAuthorizationResponse.apply _)
}

case class SlackSearchQuery(queryString: String)

case class SlackSearchResponse(query: String, messages: JsObject)
object SlackSearchResponse {
  implicit val reads = Json.reads[SlackSearchResponse]
}
