package com.keepit.model

import com.keepit.common.db.{ States, State, Id }
import com.keepit.common.time._
import org.joda.time.DateTime

abstract class SlackIntegrationStatus(status: String)
object SlackIntegrationStatus {
  case object On extends SlackIntegrationStatus("on")
  case object Off extends SlackIntegrationStatus("off")
}

case class SlackIncomingWebhook(
  id: Option[Id[SlackIncomingWebhook]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackIncomingWebhook] = SlackIncomingWebhookStates.ACTIVE,
  ownerId: Id[SlackTeamMembership],
  channel: SlackChannel,
  url: String,
  configurationUrl: String,
  lastUsedAt: DateTime, // all hooks should be tested once upon initial integration
  failureCount: Int = 0,
  failureInfo: Option[String])

object SlackIncomingWebhookStates extends States[SlackIncomingWebhook]
