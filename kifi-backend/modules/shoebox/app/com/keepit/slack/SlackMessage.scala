package com.keepit.slack

import com.keepit.common.strings.StringWithReplacements
import com.kifi.macros.json
import play.api.http.Status._
import play.api.libs.json._
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

sealed abstract class SlackAPIFail(val status: Int, val msg: String, val payload: JsValue) extends Exception(s"$status response: $msg ($payload)") {
  def asResponse = Status(status)(Json.obj("error" -> msg, "payload" -> payload, "status" -> status))
}
object SlackAPIFail {
  case class Generic(override val status: Int, js: JsValue) extends SlackAPIFail(status, "api_error", js)
  case class ParseError(js: JsValue, err: JsError) extends SlackAPIFail(OK, "unparseable_payload", js)
  case class StateError(state: String) extends SlackAPIFail(OK, "broken_state", JsString(state))
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
@json
case class SlackIncomingWebhook(
  url: String,
  channel: String,
  configUrl: String)

case class SlackAuthorizationResponse(
  accessToken: SlackAccessToken,
  scopes: Set[SlackAuthScope],
  teamName: String,
  incomingWebhook: Option[SlackIncomingWebhook])
object SlackAuthorizationResponse {
  private implicit val slackScopesReads = SlackAuthScope.slackReads
  implicit val reads: Reads[SlackAuthorizationResponse] = Json.reads[SlackAuthorizationResponse]
}

case class SlackSearchQuery(queryString: String)

@json
case class SlackSearchResponse(query: String, messages: JsObject)
