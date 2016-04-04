package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.json.EnumFormat
import com.keepit.common.mail.EmailAddress
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time._
import com.keepit.slack.models.{ SlackTeamName, SlackTeamId, SlackMessage }
import com.keepit.social.Author
import com.keepit.social.twitter.RawTweet
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.{ Failure, Success, Try }

case class KeepSourceAttribution(
    id: Option[Id[KeepSourceAttribution]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    keepId: Id[Keep],
    author: Author,
    attribution: RawSourceAttribution,
    state: State[KeepSourceAttribution] = KeepSourceAttributionStates.ACTIVE) extends ModelWithState[KeepSourceAttribution] {

  def withId(id: Id[KeepSourceAttribution]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[KeepSourceAttribution]) = this.copy(state = state)
}

case class UnknownAttributionTypeException(name: String) extends Exception(s"Unknown keep attribution type: $name")

object KeepSourceAttributionStates extends States[KeepSourceAttribution]

sealed abstract class KeepAttributionType(val name: String)

object KeepAttributionType extends Enumerator[KeepAttributionType] {
  case object Twitter extends KeepAttributionType("twitter")
  case object Slack extends KeepAttributionType("slack")
  case object Kifi extends KeepAttributionType("kifi")
  private def all = _all
  def fromString(name: String): Option[KeepAttributionType] = all.find(_.name equalsIgnoreCase name)

  implicit val format: Format[KeepAttributionType] = EnumFormat.format(fromString, _.name, all.map(_.name).toSet)
}

sealed trait RawSourceAttribution
object RawSourceAttribution {
  import KeepAttributionType._
  def toJson(attr: RawSourceAttribution): (KeepAttributionType, JsValue) = {
    attr match {
      case t: RawTwitterAttribution => (Twitter, RawTwitterAttribution.format.writes(t))
      case s: RawSlackAttribution => (Slack, RawSlackAttribution.format.writes(s))
      case k: RawKifiAttribution => (Kifi, RawKifiAttribution.format.writes(k))
    }
  }

  def fromJson(attrType: KeepAttributionType, attrJson: JsValue): JsResult[RawSourceAttribution] = {
    attrType match {
      case Twitter => RawTwitterAttribution.format.reads(attrJson)
      case Slack => RawSlackAttribution.format.reads(attrJson)
      case Kifi => RawKifiAttribution.format.reads(attrJson)
    }
  }

  val internalFormat = new OFormat[RawSourceAttribution] {
    def writes(source: RawSourceAttribution) = {
      val (attrType, attrJs) = toJson(source)
      Json.obj(
        "type" -> attrType,
        "source" -> attrJs
      )
    }

    def reads(value: JsValue): JsResult[RawSourceAttribution] = {
      for {
        attrType <- (value \ "type").validate[KeepAttributionType]
        attr <- fromJson(attrType, value \ "source")
      } yield attr
    }
  }
}

case class RawTwitterAttribution(tweet: RawTweet) extends RawSourceAttribution
object RawTwitterAttribution {
  implicit val format = Json.format[RawTwitterAttribution]
}

case class RawSlackAttribution(message: SlackMessage, teamId: SlackTeamId) extends RawSourceAttribution
object RawSlackAttribution {
  implicit val format = Json.format[RawSlackAttribution]
}

case class RawKifiAttribution(keptBy: Id[User], connections: KeepRecipients, source: KeepSource) extends RawSourceAttribution
object RawKifiAttribution {
  implicit val format = Json.format[RawKifiAttribution]
}
