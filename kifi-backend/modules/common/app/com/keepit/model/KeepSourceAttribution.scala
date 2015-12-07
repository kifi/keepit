package com.keepit.model

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time._
import com.keepit.slack.models.SlackMessage
import com.keepit.social.BasicUser
import com.keepit.social.twitter.{ RawTweet, TwitterHandle }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.{ Failure, Success, Try }

case class KeepSourceAttribution(
    id: Option[Id[KeepSourceAttribution]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    keepId: Id[Keep],
    attribution: SourceAttribution,
    state: State[KeepSourceAttribution] = KeepSourceAttributionStates.ACTIVE) extends ModelWithState[KeepSourceAttribution] {

  def withId(id: Id[KeepSourceAttribution]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

case class UnknownAttributionTypeException(name: String) extends Exception(s"Unknown keep attribution type: $name")

object KeepSourceAttributionStates extends States[KeepSourceAttribution]

sealed abstract class KeepAttributionType(val name: String)

object KeepAttributionType extends Enumerator[KeepAttributionType] {
  case object Twitter extends KeepAttributionType("twitter")
  case object TwitterPartial extends KeepAttributionType("twitter-partial")
  case object Slack extends KeepAttributionType("slack")
  def all = _all
  def fromString(name: String): Try[KeepAttributionType] = {
    all.collectFirst {
      case attrType if attrType.name equalsIgnoreCase name => Success(attrType)
    } getOrElse Failure(UnknownAttributionTypeException(name))
  }

  implicit val format = Format[KeepAttributionType](
    Reads(_.validate[String].flatMap(name => KeepAttributionType.fromString(name).map(JsSuccess(_)).recover { case error => JsError(error.getMessage) }.get)),
    Writes(attrType => JsString(attrType.name))
  )
}

sealed trait SourceAttribution
object SourceAttribution {
  import KeepAttributionType._
  def toJson(attr: SourceAttribution): (KeepAttributionType, JsValue) = {
    attr match {
      case t: TwitterAttribution => (Twitter, TwitterAttribution.format.writes(t))
      case x: PartialTwitterAttribution => (TwitterPartial, PartialTwitterAttribution.format.writes(x))
      case s: SlackAttribution => (Slack, SlackAttribution.format.writes(s))
    }
  }

  def fromJson(attrType: KeepAttributionType, attrJson: JsValue): JsResult[SourceAttribution] = {
    attrType match {
      case Twitter => TwitterAttribution.format.reads(attrJson)
      case TwitterPartial => PartialTwitterAttribution.format.reads(attrJson)
      case Slack => SlackAttribution.format.reads(attrJson)
    }
  }

  implicit val format = new Format[SourceAttribution] {
    def writes(source: SourceAttribution): JsValue = {
      val (attrType, attrJs) = toJson(source)
      Json.obj(
        "type" -> attrType,
        "source" -> attrJs
      )
    }

    def reads(value: JsValue): JsResult[SourceAttribution] = {
      for {
        attrType <- (value \ "type").validate[KeepAttributionType]
        attr <- fromJson(attrType, value \ "source")
      } yield attr
    }
  }

  implicit val deprecatedWrites = new Writes[(SourceAttribution, Option[BasicUser])] {
    def writes(x: (SourceAttribution, Option[BasicUser])): JsValue = {
      val (source, userOpt) = x
      val (attrTypeString, attrJs) = toJson(source) match {
        case (TwitterPartial, value) => (Twitter.name, value)
        case (Twitter, value) =>
          val updatedValue = for {
            obj <- value.validate[JsObject]
            sourceObj <- (value \ "source").validate[JsObject]
            oldFields <- PartialTwitterAttribution.fromRawTweetJson(sourceObj).flatMap(PartialTwitterAttribution.format.writes(_).validate[JsObject])
          } yield {
            (obj - "source") + ("source" -> (sourceObj ++ oldFields))
          }
          (Twitter.name, updatedValue getOrElse value)
        case (Slack, value) => (Slack.name, value)
      }
      Json.obj(attrTypeString -> attrJs, "kifi" -> userOpt)
    }
  }
}

case class PartialTwitterAttribution(idString: String, screenName: TwitterHandle) extends SourceAttribution {
  def getOriginalURL: String = s"https://twitter.com/${screenName.value}/status/$idString"
}

object PartialTwitterAttribution {
  implicit val format = Json.format[PartialTwitterAttribution]

  def fromRawTweetJson(js: JsValue): JsResult[PartialTwitterAttribution] = {
    for {
      idString <- (js \ "id_str").validate[String]
      screenName <- (js \ "user" \ "screen_name").validate[TwitterHandle]
    } yield PartialTwitterAttribution(idString, screenName)
  }
}

case class SlackAttribution(message: SlackMessage) extends SourceAttribution
object SlackAttribution {
  implicit val format = Json.format[SlackAttribution]
}

case class TwitterAttribution(tweet: RawTweet) extends SourceAttribution
object TwitterAttribution {
  implicit val format = Json.format[TwitterAttribution]
}
