package com.keepit.slack.models

import com.keepit.common.strings._
import com.keepit.common.db.slick.DataBaseComponent
import play.api.libs.json.{ Json, Writes }

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
  def channelName(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackChannelName, String](_.value, SlackChannelName(_))
  }

  def timestamp(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackMessageTimestamp, String](_.value, SlackMessageTimestamp(_))
  }
}

case class SlackMessageRequest( // https://api.slack.com/incoming-webhooks
  text: String,
  channel: Option[SlackChannelName],
  username: String,
  iconUrl: String,
  attachments: Seq[SlackAttachment],
  unfurlLinks: Boolean,
  unfurlMedia: Boolean)

object SlackMessageRequest {

  val kifiIconUrl = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png"

  def fromKifi(text: String, channel: Option[SlackChannelName] = None, attachments: Seq[SlackAttachment] = Seq.empty) = SlackMessageRequest(
    text,
    channel = channel,
    username = "Kifi",
    iconUrl = kifiIconUrl,
    attachments = attachments,
    unfurlLinks = true,
    unfurlMedia = true
  )

  implicit val writes: Writes[SlackMessageRequest] = Writes { o =>
    Json.obj(
      "text" -> o.text,
      "channel" -> o.channel,
      "username" -> o.username,
      "icon_url" -> o.iconUrl,
      "attachments" -> o.attachments,
      "unfurl_links" -> o.unfurlLinks,
      "unfurl_media" -> o.unfurlMedia
    )
  }

  def escapeSegment(segment: String): String = segment.replaceAllLiterally("<" -> "&lt;", ">" -> "&gt;", "&" -> "&amp")
}