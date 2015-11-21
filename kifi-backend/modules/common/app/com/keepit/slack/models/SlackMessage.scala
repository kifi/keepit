package com.keepit.slack.models

import com.keepit.common.db.Id
import com.keepit.common.reflection.Enumerator
import com.keepit.model.KeepAttributionType._
import com.keepit.model.Library
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.kifi.macros.json

import scala.util.{ Failure, Success, Try }

object KifiSlackApp {
  val SLACK_CLIENT_ID = "2348051170.15031499078"
  val SLACK_CLIENT_SECRET = "ad688ad730192eabe0bdc6675975f3fc"
  val KIFI_SLACK_REDIRECT_URI = "https://www.kifi.com/oauth2/slack"
  val SLACK_COMMAND_TOKEN = SlackCommandToken("g4gyK5XEFCDm4RqgsyjGKPCD")
}

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
  fallback: Option[String] = None,
  color: Option[String] = None,
  pretext: Option[String] = None,
  service: Option[String] = None,
  author: Option[SlackAttachment.Author] = None,
  title: Option[SlackAttachment.Title] = None,
  text: Option[String] = None,
  fields: Seq[SlackAttachment.Field] = Seq.empty,
  fromUrl: Option[String] = None,
  imageUrl: Option[String] = None,
  thumbUrl: Option[String] = None)

object SlackAttachment {
  case class Author(name: String, link: Option[String], icon: Option[String])
  case class Title(value: String, link: Option[String])
  @json case class Field(title: String, value: String, short: Boolean)

  def fromTitleAndImage(title: Title, thumbUrl: Option[String], color: String) = SlackAttachment(
    fallback = None,
    color = Some(color),
    pretext = None,
    service = None,
    author = None,
    title = Some(title),
    text = None,
    fields = Seq.empty,
    fromUrl = None,
    imageUrl = None,
    thumbUrl = thumbUrl
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
    (__ \ "thumb_url").formatNullable[String]
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

@json
case class SlackReaction(value: String)
object SlackReaction {
  val checkMark = SlackReaction("heavy_check_mark")
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

@json
case class SlackCommandToken(value: String)

case class SlackCommandRequest(
  token: SlackCommandToken,
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
    (__ \ "token").read[SlackCommandToken] and
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

  case class InvalidSlackCommandRequest(commandForm: Map[String, Seq[String]], missingKey: String) extends Exception(s"Invalid Slack command request, $missingKey not found: $commandForm")

  def fromSlack(commandForm: Map[String, Seq[String]]): Try[SlackCommandRequest] = Try {

    def get(key: String): String = commandForm.get(key).flatMap(_.headOption).getOrElse { throw new InvalidSlackCommandRequest(commandForm, key) }

    SlackCommandRequest(
      SlackCommandToken(get("token")),
      SlackTeamId(get("team_id")),
      SlackTeamDomain(get("team_domain")),
      SlackChannelId(get("channel_id")),
      SlackChannelName(get("channel_name")),
      SlackUserId(get("user_id")),
      SlackUsername(get("user_name")),
      SlackCommand.fromString(get("command")).get,
      get("text"),
      get("response_url")
    )
  }
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

@json
case class SlackChannelToLibrarySummary(
  teamId: SlackTeamId,
  channelId: SlackChannelId,
  libraryId: Id[Library],
  on: Boolean,
  lastMessageTimestamp: Option[SlackMessageTimestamp])
