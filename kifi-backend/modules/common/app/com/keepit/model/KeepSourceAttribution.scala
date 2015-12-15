package com.keepit.model

import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time._
import com.keepit.slack.models.SlackMessage
import com.keepit.social.BasicUser
import com.keepit.social.twitter.{ RawTweet, TwitterHandle }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._

import scala.concurrent.duration.Duration
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
      case s: SlackAttribution => (Slack, SlackAttribution.format.writes(s))
    }
  }

  def fromJson(attrType: KeepAttributionType, attrJson: JsValue): JsResult[SourceAttribution] = {
    attrType match {
      case Twitter => TwitterAttribution.format.reads(attrJson)
      case Slack => SlackAttribution.format.reads(attrJson)
    }
  }

  val internalFormat = new OFormat[SourceAttribution] {
    def writes(source: SourceAttribution) = {
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

  val externalWrites: OWrites[SourceAttribution] = OWrites { source =>
    val (attrType, attrJs) = toJson(source)
    Json.obj(attrType.name -> attrJs)
  }

  val externalWritesWithBasicUser: OWrites[(SourceAttribution, Option[BasicUser])] = OWrites {
    case (source, userOpt) => externalWrites.writes(source) + ("kifi" -> Json.toJson(userOpt))
  }

  val deprecatedWrites = externalWritesWithBasicUser.transform { value =>
    val updatedValue = for {
      obj <- value.validate[JsObject]
      tweeterObj <- (value \ "twitter").validate[JsObject]
      tweet <- (tweeterObj \ "tweet").validate[RawTweet]
    } yield {
      (obj - "twitter") + ("twitter" -> (tweeterObj ++ Json.obj("idString" -> tweet.id.id.toString, "screenName" -> tweet.user.screenName.value)))
    }
    updatedValue getOrElse value
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

case class SourceAttributionKeepIdKey(keepId: Id[Keep]) extends Key[SourceAttribution] {
  override val version = 1
  val namespace = "source_attribution_by_keep_id"
  def toKey(): String = keepId.id.toString
}

class SourceAttributionKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SourceAttributionKeepIdKey, SourceAttribution](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(SourceAttribution.internalFormat)
