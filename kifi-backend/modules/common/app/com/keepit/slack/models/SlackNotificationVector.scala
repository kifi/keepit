package com.keepit.slack.models

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.User
import play.api.libs.json.{ Json, Format }

import scala.concurrent.duration.Duration

case class SlackNotificationVector(
  slackUserId: SlackUserId,
  slackToken: SlackBotAccessToken)

object SlackNotificationVector {
  implicit val format: Format[SlackNotificationVector] = Json.format[SlackNotificationVector]
}

case class SlackNotificationVectorKey(userId: Id[User]) extends Key[Seq[SlackNotificationVector]] {
  override val version = 1
  val namespace = "slack_notification_vector"
  def toKey(): String = userId.id.toString
}

class SlackNotificationVectorCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SlackNotificationVectorKey, Seq[SlackNotificationVector]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
