package com.keepit.slack.models

import com.keepit.common.routes.Param
import com.keepit.common.util.DescriptionElements
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class SlackIncomingWebhook(
  channelId: Option[SlackChannelId],
  channelName: SlackChannelName,
  url: String,
  configUrl: String)
object SlackIncomingWebhook {
  implicit val reads: Reads[SlackIncomingWebhook] = (
    (__ \ 'channel_id).readNullable[SlackChannelId] and
    (__ \ 'channel).read[SlackChannelName] and
    (__ \ 'url).read[String] and
    (__ \ 'configuration_url).read[String]
  )(SlackIncomingWebhook.apply _)
}

case class SlackMessageRequest( // https://api.slack.com/incoming-webhooks
    text: String,
    username: String,
    iconUrl: String,
    attachments: Seq[SlackAttachment],
    unfurlLinks: Boolean,
    unfurlMedia: Boolean) {
  def quiet = this.copy(unfurlLinks = false, unfurlMedia = false)
  def withAttachments(newAttachments: Seq[SlackAttachment]) = this.copy(attachments = newAttachments)
  def asUrlParams: Seq[Param] = Seq("text" -> text, "attachments" -> Json.stringify(Json.toJson(attachments)), "username" -> username, "icon_url" -> iconUrl, "unfurl_links" -> unfurlLinks, "unfurl_media" -> unfurlMedia)
}

object SlackMessageRequest {

  val kifiIconUrl = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png"
  val pandaIconUrl = "http://i.imgur.com/A86F6aB.png"

  def fromKifi(text: String, attachments: Seq[SlackAttachment] = Seq.empty) = SlackMessageRequest(
    text,
    username = "Kifi",
    iconUrl = kifiIconUrl,
    attachments = attachments,
    unfurlLinks = false,
    unfurlMedia = false
  )

  def inhouse(txt: DescriptionElements, attachments: Seq[SlackAttachment] = Seq.empty) = SlackMessageRequest(
    text = DescriptionElements.formatForSlack(txt),
    username = "inhouse-kifi-bot",
    iconUrl = pandaIconUrl,
    attachments = attachments,
    unfurlLinks = false,
    unfurlMedia = false
  )

  implicit val writes: Writes[SlackMessageRequest] = Writes { o =>
    Json.obj(
      "text" -> o.text,
      "username" -> o.username,
      "icon_url" -> o.iconUrl,
      "attachments" -> o.attachments,
      "unfurl_links" -> o.unfurlLinks,
      "unfurl_media" -> o.unfurlMedia
    )
  }
}
