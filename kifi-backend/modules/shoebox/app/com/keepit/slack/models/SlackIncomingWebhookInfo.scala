package com.keepit.slack.models

import com.keepit.common.db.{ Id, State, States }
import com.keepit.common.time._
import org.joda.time.DateTime

abstract class SlackIntegrationStatus(status: String)
object SlackIntegrationStatus {
  case object On extends SlackIntegrationStatus("on")
  case object Off extends SlackIntegrationStatus("off")
}

case class SlackIncomingWebhookInfo(
  id: Option[Id[SlackIncomingWebhookInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackIncomingWebhookInfo] = SlackIncomingWebhookInfoStates.ACTIVE,
  membershipId: Id[SlackTeamMembership],
  webhook: SlackIncomingWebhook,
  lastUsedAt: DateTime, // all hooks should be tested once upon initial integration
  failureCount: Int = 0,
  failureInfo: Option[String])

object SlackIncomingWebhookInfoStates extends States[SlackIncomingWebhookInfo]
