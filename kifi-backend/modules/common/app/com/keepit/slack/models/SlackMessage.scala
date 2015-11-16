package com.keepit.slack.models

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.kifi.macros.json

@json case class SlackUserId(value: String)
@json case class SlackUsername(value: String)

@json case class SlackTeamId(value: String)
@json case class SlackTeamName(value: String)

@json case class SlackChannelId(value: String) // broad sense, can be channel, group or DM
@json case class SlackChannelName(value: String) // broad sense, can be channel, group or DM
@json case class SlackChannel(id: SlackChannelId, name: SlackChannelName)

@json case class SlackAccessToken(token: String)

@json case class SlackMessageTimestamp(value: String) extends Ordered[SlackMessageTimestamp] { // channel-specific timestamp
  def compare(that: SlackMessageTimestamp) = value compare that.value
}

@json case class SlackMessageType(value: String)

case class SlackAttachment(
  fallback: String,
  color: Option[String],
  pretext: Option[String],
  service: Option[String],
  author: Option[SlackAttachment.Author],
  title: Option[SlackAttachment.Title],
  text: Option[String],
  fields: Seq[SlackAttachment.Field],
  imageUrl: Option[String],
  thumbUrl: Option[String])

object SlackAttachment {
  case class Author(name: String, link: Option[String], icon: Option[String])
  case class Title(value: String, link: Option[String])
  @json case class Field(title: String, value: String, short: Boolean)

  def minimal(fallback: String, text: String) = SlackAttachment(
    fallback = fallback,
    color = None,
    pretext = None,
    service = None,
    author = None,
    title = None,
    text = None,
    fields = Seq.empty,
    imageUrl = None,
    thumbUrl = None
  )

  def applyFromSlack(
    fallback: String,
    color: Option[String],
    pretext: Option[String],
    service: Option[String],
    authorName: Option[String],
    authorLink: Option[String],
    authorIcon: Option[String],
    titleValue: Option[String],
    titleLink: Option[String],
    text: Option[String],
    fields: Option[Seq[SlackAttachment.Field]],
    imageUrl: Option[String],
    thumbUrl: Option[String]): SlackAttachment = {
    val author = authorName.map(Author(_, authorLink, authorIcon))
    val title = titleValue.map(Title(_, titleLink))
    SlackAttachment(fallback, color, pretext, service, author, title, text, fields.getOrElse(Seq.empty), imageUrl, thumbUrl)
  }

  def unapplyToSlack(attachment: SlackAttachment) = Some((
    attachment.fallback: String,
    attachment.color: Option[String],
    attachment.pretext: Option[String],
    attachment.service: Option[String],
    attachment.author.map(_.name): Option[String],
    attachment.author.flatMap(_.link): Option[String],
    attachment.author.flatMap(_.icon): Option[String],
    attachment.title.map(_.value): Option[String],
    attachment.title.flatMap(_.link): Option[String],
    attachment.text: Option[String],
    Some(attachment.fields).filter(_.nonEmpty): Option[Seq[SlackAttachment.Field]],
    attachment.imageUrl: Option[String],
    attachment.thumbUrl: Option[String]
  ))

  implicit val format: Format[SlackAttachment] = (
    (__ \ "fallback").format[String] and
    (__ \ "color").formatNullable[String] and
    (__ \ "pretext").formatNullable[String] and
    (__ \ "service_name").formatNullable[String] and
    (__ \ "author_name").formatNullable[String] and
    (__ \ "author_link").formatNullable[String] and
    (__ \ "author_icon").formatNullable[String] and
    (__ \ "title").formatNullable[String] and
    (__ \ "title_link").formatNullable[String] and
    (__ \ "text").formatNullable[String] and
    (__ \ "fields").formatNullable[Seq[SlackAttachment.Field]] and
    (__ \ "image_url").formatNullable[String] and
    (__ \ "image_thumb").formatNullable[String]
  )(applyFromSlack, unlift(unapplyToSlack))
}

case class SlackMessage(
  messageType: SlackMessageType,
  userId: SlackUserId,
  timestamp: SlackMessageTimestamp,
  channel: SlackChannel,
  text: String,
  attachments: Seq[SlackAttachment],
  permalink: String)

object SlackMessage {
  implicit val slackFormat = (
    (__ \ "type").format[SlackMessageType] and
    (__ \ "user").format[SlackUserId] and
    (__ \ "ts").format[SlackMessageTimestamp] and
    (__ \ "channel").format[SlackChannel] and
    (__ \ "text").format[String] and
    (__ \ "attachments").formatNullable[Seq[SlackAttachment]].inmap[Seq[SlackAttachment]](_.getOrElse(Seq.empty), Some(_).filter(_.nonEmpty)) and
    (__ \ "permalink").format[String]
  )(apply, unlift(unapply))

}
