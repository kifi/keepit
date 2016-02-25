package com.keepit.slack.models

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.model.Keep
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

case class SlackPushForKeepTimestampKey(integrationId: Id[LibraryToSlackChannel], keepId: Id[Keep]) extends Key[SlackTimestamp] {
  override val version = 1
  val namespace = "slack_push_for_keep"
  def toKey(): String = s"${integrationId.id}_${keepId.id}"
}

class SlackPushForKeepTimestampCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SlackPushForKeepTimestampKey, SlackTimestamp](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SlackPushForMessageTimestampKey(integrationId: Id[LibraryToSlackChannel], msgId: Id[Message]) extends Key[SlackTimestamp] {
  override val version = 1
  val namespace = "slack_push_for_message"
  def toKey(): String = s"${integrationId.id}_${msgId.id}"
}

class SlackPushForMessageTimestampCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SlackPushForMessageTimestampKey, SlackTimestamp](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SlackPushForKeep(
  id: Option[Id[SlackPushForKeep]],
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackPushForKeep] = SlackPushForKeepStates.ACTIVE,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  integrationId: Id[LibraryToSlackChannel],
  keepId: Id[Keep],
  timestamp: SlackTimestamp,
  text: String)
    extends Model[SlackPushForKeep] with ModelWithState[SlackPushForKeep] {
  def withId(id: Id[SlackPushForKeep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackPushForKeepStates.ACTIVE
}
object SlackPushForKeepStates extends States[SlackPushForKeep]

case class SlackPushForMessage(
  id: Option[Id[SlackPushForMessage]],
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackPushForMessage] = SlackPushForMessageStates.ACTIVE,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  integrationId: Id[LibraryToSlackChannel],
  messageId: Id[Message],
  timestamp: SlackTimestamp,
  text: String)
    extends Model[SlackPushForMessage] with ModelWithState[SlackPushForMessage] {
  def withId(id: Id[SlackPushForMessage]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackPushForMessageStates.ACTIVE
}
object SlackPushForMessageStates extends States[SlackPushForMessage]
