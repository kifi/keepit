package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import com.keepit.social.twitter.{ TwitterUserId, TwitterHandle, TwitterStatusId, RawTweet }
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

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

  def fromRawSourceAttribution(source: RawSourceAttribution): SourceAttribution = source match {
    case RawTwitterAttribution(tweet) => TwitterAttribution(BasicTweet.fromRawTweet(tweet))
    case RawSlackAttribution(message) => SlackAttribution(BasicSlackMessage.fromSlackMessage(message))
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
      tweet <- (tweeterObj \ "tweet").validate(BasicTweet.format)
    } yield {
      (obj - "twitter") + ("twitter" -> (tweeterObj ++ Json.obj("idString" -> tweet.id.id.toString, "screenName" -> tweet.user.screenName.value)))
    }
    updatedValue getOrElse value
  }
}

case class BasicTweet(id: TwitterStatusId, user: RawTweet.User, permalink: String)
object BasicTweet {
  private val userFormat: Format[RawTweet.User] = (
    (__ \ 'name).format[String] and
    (__ \ 'screen_name).format[TwitterHandle] and
    (__ \ 'id_str).format(Format(TwitterUserId.stringReads, Writes[TwitterUserId](id => JsString(id.id.toString)))) and
    (__ \ 'profile_image_url_https).format[String]
  )(RawTweet.User.apply _, unlift((RawTweet.User.unapply)))

  implicit val format = (
    (__ \ 'id_str).format(Format(TwitterStatusId.stringReads, Writes[TwitterStatusId](id => JsString(id.id.toString)))) and
    (__ \ 'user).format(userFormat) and
    (__ \ 'permalink).format[String]
  )(BasicTweet.apply _, unlift((BasicTweet.unapply)))

  def fromRawTweet(tweet: RawTweet): BasicTweet = BasicTweet(tweet.id, tweet.user, tweet.getUrl)
}

case class TwitterAttribution(tweet: BasicTweet) extends SourceAttribution
object TwitterAttribution {
  implicit val format = Json.format[TwitterAttribution]
}

case class BasicSlackMessage(channel: SlackChannelIdAndName, userId: SlackUserId, username: SlackUsername, timestamp: SlackTimestamp, permalink: String)
object BasicSlackMessage {
  implicit val format = Json.format[BasicSlackMessage]
  def fromSlackMessage(message: SlackMessage): BasicSlackMessage = BasicSlackMessage(message.channel, message.userId, message.username, message.timestamp, message.permalink)
}

case class SlackAttribution(message: BasicSlackMessage) extends SourceAttribution
object SlackAttribution {
  implicit val format = Json.format[SlackAttribution]
}

case class SourceAttributionKeepIdKey(keepId: Id[Keep]) extends Key[SourceAttribution] {
  override val version = 2
  val namespace = "source_attribution_by_keep_id"
  def toKey(): String = keepId.id.toString
}

class SourceAttributionKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SourceAttributionKeepIdKey, SourceAttribution](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(SourceAttribution.internalFormat)