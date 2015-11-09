package com.keepit.slack

import com.keepit.common.logging.Logging
import com.keepit.common.net.{ HttpClient, DirectUrl }
import com.kifi.macros.json
import play.api.Mode.Mode
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }

@json
case class SlackAttachment(fallback: String, text: String)

case class BasicSlackMessage( // https://api.slack.com/incoming-webhooks
  text: String,
  channel: Option[String] = None,
  username: String = "Kifi",
  iconUrl: String = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png",
  attachments: Seq[SlackAttachment] = Seq.empty)

object BasicSlackMessage {
  implicit val writes: Writes[BasicSlackMessage] = Writes { o =>
    Json.obj("text" -> o.text, "channel" -> o.channel, "username" -> o.username, "icon_url" -> o.iconUrl, "attachments" -> o.attachments)
  }
}

// TODO(ryan): this is garbage, put real fields in here
case class SlackResponse(
  channel: String,
  ok: Boolean)
object SlackResponse {
  val FAKE_SUCCESS = SlackResponse("dummy", ok = true)
  implicit val format: Format[SlackResponse] = (
    (__ \ 'channel).format[String] and
    (__ \ 'ok).format[Boolean]
  )(SlackResponse.apply, unlift(SlackResponse.unapply))
}

trait SlackClient {
  def sendToSlack(url: String, msg: BasicSlackMessage): Future[SlackResponse]
}

class SlackClientImpl(
  httpClient: HttpClient,
  mode: Mode,
  implicit val ec: ExecutionContext)
    extends SlackClient with Logging {
  def sendToSlack(url: String, msg: BasicSlackMessage): Future[SlackResponse] = {
    httpClient.postFuture(DirectUrl(url), Json.toJson(msg)).map { clientResponse =>
      clientResponse.json.as[SlackResponse]
    }
  }
}
