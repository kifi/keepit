package com.keepit.slack

import com.keepit.common.reflection.Enumerator
import com.keepit.common.strings.StringWithReplacements
import com.kifi.macros.json
import play.api.libs.json._
import play.api.mvc.Results.Status
import play.api.http.Status._

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
  def asResponse = Status(status)(Json.obj("error" -> msg, "payload" -> payload))
}
object SlackAPIFail {
  case class Generic(override val status: Int, js: JsValue) extends SlackAPIFail(status, "api_error", js)
  case class ParseError(js: JsValue, err: JsError) extends SlackAPIFail(OK, "unparseable_payload", js)
  case class StateError(state: String) extends SlackAPIFail(OK, "broken_state", JsString(state))
}

sealed abstract class SlackAuthScope(val value: String)
object SlackAuthScope extends Enumerator[SlackAuthScope] {
  case object ChannelsWrite extends SlackAuthScope("channels:write")
  case object ChannelsHistory extends SlackAuthScope("channels:history")
  case object ChannelsRead extends SlackAuthScope("channels:read")
  case object ChatWrite extends SlackAuthScope("chat:write")
  case object ChatWriteBot extends SlackAuthScope("chat:write:bot")
  case object ChatWriteUser extends SlackAuthScope("chat:write:user")
  case object EmojiRead extends SlackAuthScope("emoji:read")
  case object FilesWriteUser extends SlackAuthScope("files:write:user")
  case object FilesRead extends SlackAuthScope("files:read")
  case object GroupsWrite extends SlackAuthScope("groups:write")
  case object GroupsHistory extends SlackAuthScope("groups:history")
  case object GroupsRead extends SlackAuthScope("groups:read")
  case object ImWrite extends SlackAuthScope("im:write")
  case object ImHistory extends SlackAuthScope("im:history")
  case object ImRead extends SlackAuthScope("im:read")
  case object MpimWrite extends SlackAuthScope("mpim:write")
  case object MpimHistory extends SlackAuthScope("mpim:history")
  case object MpimRead extends SlackAuthScope("mpim:read")
  case object PinsWrite extends SlackAuthScope("pins:write")
  case object PinsRead extends SlackAuthScope("pins:read")
  case object ReactionsWrite extends SlackAuthScope("reactions:write")
  case object ReactionsRead extends SlackAuthScope("reactions:read")
  case object SearchRead extends SlackAuthScope("search:read")
  case object StarsWrite extends SlackAuthScope("stars:write")
  case object StarsRead extends SlackAuthScope("stars:read")
  case object TeamRead extends SlackAuthScope("team:read")
  case object UsersRead extends SlackAuthScope("users:read")
  case object UsersWrite extends SlackAuthScope("users:write")
  def all = _all.toSet

  def apply(str: String): SlackAuthScope = all.find(_.value == str).get
  implicit val setFormat: Format[Set[SlackAuthScope]] = Format(
    Reads { j => j.validate[String].map(s => s.split(",").toSet.map(SlackAuthScope.apply)) },
    Writes { scopes => JsString(scopes.map(_.value).mkString(",")) }
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
@json
case class SlackAuthorizationResponse(
  accessToken: SlackAccessToken,
  scopes: Set[SlackAuthScope],
  teamName: String,
  incomingWebhook: Option[SlackIncomingWebhook])
