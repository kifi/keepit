package com.keepit.model

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time._
import com.keepit.slack.models.SlackMessage
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.{ Failure, Success, Try }

@json case class TwitterHandle(value: String) {
  override def toString = value
}

@json case class TwitterId(id: Long) {
  // https://groups.google.com/forum/#!topic/twitter-development-talk/ahbvo3VTIYI
  override def toString = id.toString
}
//todo: flip references i.e. kill Keep.attributionId and introduce KeepSourceAttribution.keepId
case class KeepSourceAttribution(
    id: Option[Id[KeepSourceAttribution]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    keepId: Option[Id[Keep]],
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
      case x: TwitterAttribution => (Twitter, TwitterAttribution.format.writes(x))
      case s: SlackAttribution => (Slack, SlackAttribution.format.writes(s))
    }
  }

  def fromJson(attrType: KeepAttributionType, attrJson: JsValue): JsResult[SourceAttribution] = {
    attrType match {
      case Twitter => TwitterAttribution.format.reads(attrJson)
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

  implicit val deprecatedWrites = new Writes[KeepSourceAttribution] {
    def writes(x: KeepSourceAttribution): JsValue = {
      val (attrType, attrJs) = toJson(x.attribution)
      Json.obj(attrType.name -> attrJs)
    }
  }
}

case class TwitterAttribution(idString: String, screenName: TwitterHandle) extends SourceAttribution {
  def getOriginalURL: String = s"https://twitter.com/${screenName.value}/status/$idString"
}

object TwitterAttribution {
  implicit val format = Json.format[TwitterAttribution]

  def fromRawTweetJson(js: JsValue): JsResult[TwitterAttribution] = {
    for {
      idString <- (js \ "id_str").validate[String]
      screenName <- (js \ "user" \ "screen_name").validate[TwitterHandle]
    } yield TwitterAttribution(idString, screenName)
  }
}

case class SlackAttribution(message: SlackMessage) extends SourceAttribution
object SlackAttribution {
  implicit val format = Json.format[SlackAttribution]
}
