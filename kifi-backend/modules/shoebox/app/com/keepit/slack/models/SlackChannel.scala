package com.keepit.slack.models

import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._

case class SlackChannel(
  id: Option[Id[SlackChannel]],
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackChannel] = SlackChannelStates.ACTIVE,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  slackChannelName: SlackChannelName,
  lastNotificationAt: DateTime = currentDateTime)
    extends Model[SlackChannel] with ModelWithState[SlackChannel] {

  def withId(newId: Id[SlackChannel]) = this.copy(id = Some(newId))
  def withUpdateTime(time: DateTime) = this.copy(updatedAt = time)
  def withLastNotificationAt(time: DateTime) = this.copy(lastNotificationAt = time)
}

object SlackChannelStates extends States[SlackChannel]
