package com.keepit.slack.models

import com.keepit.common.reflection.Enumerator
import com.keepit.model.KeepAttributionType._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.kifi.macros.json

import scala.util.{ Failure, Success, Try }

@json case class SlackUserId(value: String)
@json case class SlackUsername(value: String)

object SlackUsername {
  val slackbot = SlackUsername("slackbot")
  val kifibot = SlackUsername("kifi-bot")
  val doNotIngest = Set(slackbot, kifibot)
}

@json case class SlackTeamId(value: String)
@json case class SlackTeamName(value: String)
@json case class SlackTeamDomain(value: String)

@json case class SlackChannelId(value: String) // broad sense, can be channel, group or DM
@json case class SlackChannelName(value: String) // broad sense, can be channel, group or DM
@json case class SlackChannel(id: SlackChannelId, name: SlackChannelName)

@json case class SlackAccessToken(token: String)

@json case class SlackMessageTimestamp(value: String) extends Ordered[SlackMessageTimestamp] { // channel-specific timestamp
  def compare(that: SlackMessageTimestamp) = value compare that.value
  def toDateTime: DateTime = Try {
    new DateTime(value.split('.').head.toLong * 1000) // "The bit before the . is a unix timestamp, the bit after is a sequence to guarantee uniqueness."
  }.getOrElse(throw new Exception(s"Could not parse a date-time out of $value"))
}

@json case class SlackMessageType(value: String)

case class SlackAttachment(
  fallback: Option[String],
  color: Option[String],
  pretext: Option[String],
  service: Option[String],
  author: Option[SlackAttachment.Author],
  title: Option[SlackAttachment.Title],
  text: Option[String],
  fields: Seq[SlackAttachment.Field],
  fromUrl: Option[String],
  imageUrl: Option[String],
  thumbUrl: Option[String])

object SlackAttachment {
  case class Author(name: String, link: Option[String], icon: Option[String])
  case class Title(value: String, link: Option[String])
  @json case class Field(title: String, value: String, short: Boolean)

  def fromTitle(title: Title) = SlackAttachment(
    fallback = None,
    color = None,
    pretext = None,
    service = None,
    author = None,
    title = Some(title),
    text = None,
    fields = Seq.empty,
    fromUrl = None,
    imageUrl = None,
    thumbUrl = None
  )

  def minimal(fallback: String, text: String) = SlackAttachment(
    fallback = Some(fallback),
    color = None,
    pretext = None,
    service = None,
    author = None,
    title = None,
    text = Some(text),
    fields = Seq.empty,
    fromUrl = None,
    imageUrl = None,
    thumbUrl = None
  )

  def applyFromSlack(
    fallback: Option[String],
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
    fromUrl: Option[String],
    imageUrl: Option[String],
    thumbUrl: Option[String]): SlackAttachment = {
    val author = authorName.map(Author(_, authorLink, authorIcon))
    val title = titleValue.map(Title(_, titleLink))
    SlackAttachment(fallback, color, pretext, service, author, title, text, fields.getOrElse(Seq.empty), fromUrl, imageUrl, thumbUrl)
  }

  def unapplyToSlack(attachment: SlackAttachment) = Some((
    attachment.fallback: Option[String],
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
    attachment.fromUrl: Option[String],
    attachment.imageUrl: Option[String],
    attachment.thumbUrl: Option[String]
  ))

  implicit val format: Format[SlackAttachment] = (
    (__ \ "fallback").formatNullable[String] and
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
    (__ \ "from_url").formatNullable[String] and
    (__ \ "image_url").formatNullable[String] and
    (__ \ "image_thumb").formatNullable[String]
  )(applyFromSlack, unlift(unapplyToSlack))
}

case class SlackMessage(
  messageType: SlackMessageType,
  userId: SlackUserId,
  username: SlackUsername,
  timestamp: SlackMessageTimestamp,
  channel: SlackChannel,
  text: String,
  attachments: Seq[SlackAttachment],
  permalink: String)

object SlackMessage {
  implicit val slackFormat = (
    (__ \ "type").format[SlackMessageType] and
    (__ \ "user").format[SlackUserId] and
    (__ \ "username").format[SlackUsername] and
    (__ \ "ts").format[SlackMessageTimestamp] and
    (__ \ "channel").format[SlackChannel] and
    (__ \ "text").format[String] and
    (__ \ "attachments").formatNullable[Seq[SlackAttachment]].inmap[Seq[SlackAttachment]](_.getOrElse(Seq.empty), Some(_).filter(_.nonEmpty)) and
    (__ \ "permalink").format[String]
  )(apply, unlift(unapply))

}

sealed abstract class SlackCommand(val value: String)
object SlackCommand extends Enumerator[SlackCommand] {
  case class UnknownSlackCommandException(command: String) extends Exception(s"Unknown Slack command: $command")

  case object Kifi extends SlackCommand("/kifi")
  def all = _all

  def fromString(commandStr: String): Try[SlackCommand] = {
    all.collectFirst {
      case command if command.value equalsIgnoreCase commandStr => Success(command)
    } getOrElse Failure(UnknownSlackCommandException(commandStr))
  }

  implicit val format = Format[SlackCommand](
    Reads(_.validate[String].flatMap(command => SlackCommand.fromString(command).map(JsSuccess(_)).recover { case error => JsError(error.getMessage) }.get)),
    Writes(command => JsString(command.value))
  )
}
case class SlackCommandRequest(
  token: SlackAccessToken,
  teamId: SlackTeamId,
  teamDomain: SlackTeamDomain,
  channelId: SlackChannelId,
  channelName: SlackChannelName,
  userId: SlackUserId,
  username: SlackUsername,
  command: SlackCommand,
  text: String,
  responseUrl: String)

object SlackCommandRequest {
  implicit val slackReads = (
    (__ \ "token").read[SlackAccessToken] and
    (__ \ "team_id").read[SlackTeamId] and
    (__ \ "team_domain").read[SlackTeamDomain] and
    (__ \ "channel_id").read[SlackChannelId] and
    (__ \ "channel_name").read[SlackChannelName] and
    (__ \ "user_id").read[SlackUserId] and
    (__ \ "user_name").read[SlackUsername] and
    (__ \ "command").read[SlackCommand] and
    (__ \ "text").read[String] and
    (__ \ "response_url").read[String]
  )(apply _)
}

case class SlackCommandResponse(
  responseType: SlackCommandResponse.ResponseType,
  text: String,
  attachments: Seq[SlackAttachment])

object SlackCommandResponse {
  sealed abstract class ResponseType(val value: String)
  object ResponseType {
    case object InChannel extends ResponseType("in_channel")
    case object Ephemeral extends ResponseType("ephemeral")
    implicit val writes = Writes[ResponseType](responseType => JsString(responseType.value))
  }

  implicit val slackWrites = (
    (__ \ "response_type").write[ResponseType] and
    (__ \ "text").write[String] and
    (__ \ "attachments").write[Seq[SlackAttachment]]
  )(unlift(unapply))
}
