package com.keepit.model

import com.keepit.common.db.{ States, State, Id }
import org.joda.time.DateTime
import com.keepit.common.time._

case class LibraryToSlackChannel(
  id: Option[Id[LibraryToSlackChannel]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[LibraryToSlackChannel] = LibraryToSlackChannelStates.ACTIVE,
  ownerId: Id[SlackTeamMembership], // denormalized from SlackIncomingWebhook
  webhookId: Id[SlackIncomingWebhookInfo],
  library: Id[Library],
  channel: SlackChannel,
  status: SlackIntegrationStatus,
  lastProcessedAt: Option[DateTime],
  lastKeepId: Option[Id[Keep]])

object LibraryToSlackChannelStates extends States[LibraryToSlackChannel]
