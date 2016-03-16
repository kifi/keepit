package com.keepit.slack.models

import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

sealed abstract class SlackChannelId(prefix: String) {
  require(value.startsWith(prefix), s"Invalid ${this.getClass.getSimpleName}: $value")
  def value: String
}

object SlackChannelId {
  case class Public(value: String) extends SlackChannelId("C")
  case class Private(value: String) extends SlackChannelId("G")
  case class DM(value: String) extends SlackChannelId("D")
  case class User(value: String) extends SlackChannelId("U")

  private def parseChannelId(value: String): Try[SlackChannelId] = value.headOption match {
    case Some('C') => Success(Public(value))
    case Some('G') => Success(Private(value))
    case Some('D') => Success(DM(value))
    case Some('U') => Success(User(value))
    case _ => Failure(new IllegalArgumentException(s"Invalid SlackChannelId: $value"))
  }


  def apply(value: String): SlackChannelId = parseChannelId(value).get

  def parse[T <: SlackChannelId](value: String)(implicit m: ClassTag[T]): Try[T] = parseChannelId(value) match {
    case Success(channelId: T) => Success(channelId)
    case _ => Failure(new IllegalArgumentException(s"Invalid ${m.runtimeClass.getSimpleName} channel id: $value"))
  }

  implicit def format[T <: SlackChannelId](implicit m: ClassTag[T]) = Format[T](
    Reads(_.validate[String].flatMap(parse[T](_).map(JsSuccess(_)).recover { case error => JsError(error.getMessage) }.get)),
    Writes(channelId => JsString(channelId.value))
  )

  def isPublic(channelId: SlackChannelId): Boolean = channelId match {
    case Public(_) => true
    case _ => false
  }
}

@json case class SlackChannelName(value: String)
@json case class SlackChannelTopic(value: String)
@json case class SlackChannelPurpose(value: String)

@json case class SlackChannelIdAndName(id: SlackChannelId, name: SlackChannelName)
@json case class SlackChannelIdAndPrettyName(id: SlackChannelId, name: Option[SlackChannelName])
object SlackChannelIdAndPrettyName {
  def from(channelId: SlackChannelId, name: SlackChannelName): SlackChannelIdAndPrettyName = channelId match {
    case SlackChannelId.Public(_) | SlackChannelId.Private(_) => SlackChannelIdAndPrettyName(channelId, Some(name))
    case _ => SlackChannelIdAndPrettyName(channelId, None)
  }

  def from(channelIdAndName: SlackChannelIdAndName): SlackChannelIdAndPrettyName = SlackChannelIdAndPrettyName.from(channelIdAndName.id, channelIdAndName.name)
}

sealed trait SlackChannelInfo {
  def channelId: SlackChannelId
  def channelName: SlackChannelName
  def channelIdAndName = SlackChannelIdAndName(channelId, channelName)
  def members: Set[SlackUserId]
}

// There is more stuff than just this returned
case class SlackPublicChannelInfo(
    channelId: SlackChannelId.Public,
    channelName: SlackChannelName,
    creator: SlackUserId,
    createdAt: SlackTimestamp,
    isArchived: Boolean,
    isGeneral: Boolean,
    members: Set[SlackUserId],
    topic: Option[SlackChannelTopic],
    purpose: Option[SlackChannelPurpose]) extends SlackChannelInfo
object SlackPublicChannelInfo {
  implicit val reads: Reads[SlackPublicChannelInfo] = (
    (__ \ 'id).read[SlackChannelId.Public] and
    (__ \ 'name).read[String].map(name => SlackChannelName("#" + name)) and
    (__ \ 'creator).read[SlackUserId] and
    (__ \ 'created).read[Long].map(t => SlackTimestamp(t.toString)) and
    (__ \ 'is_archived).read[Boolean] and
    (__ \ 'is_general).read[Boolean] and
    (__ \ 'members).read[Set[SlackUserId]] and
    (__ \ 'topic \ 'value).readNullable[SlackChannelTopic].map(_.filter(_.value.nonEmpty)) and
    (__ \ 'purpose \ 'value).readNullable[SlackChannelPurpose].map(_.filter(_.value.nonEmpty))
  )(SlackPublicChannelInfo.apply _)
}

// There is more stuff than just this returned
case class SlackPrivateChannelInfo(
  channelId: SlackChannelId.Private,
  channelName: SlackChannelName,
  creator: SlackUserId,
  createdAt: SlackTimestamp,
  isArchived: Boolean,
  members: Set[SlackUserId],
  topic: Option[SlackChannelTopic],
  purpose: Option[SlackChannelPurpose]) extends SlackChannelInfo
object SlackPrivateChannelInfo {
  implicit val reads: Reads[SlackPrivateChannelInfo] = (
    (__ \ 'id).read[SlackChannelId.Private] and
    (__ \ 'name).read[SlackChannelName] and
    (__ \ 'creator).read[SlackUserId] and
    (__ \ 'created).read[Long].map(t => SlackTimestamp(t.toString)) and
    (__ \ 'is_archived).read[Boolean] and
    (__ \ 'members).read[Set[SlackUserId]] and
    (__ \ 'topic \ 'value).readNullable[SlackChannelTopic].map(_.filter(_.value.nonEmpty)) and
    (__ \ 'purpose \ 'value).readNullable[SlackChannelPurpose].map(_.filter(_.value.nonEmpty))
  )(SlackPrivateChannelInfo.apply _)
}
