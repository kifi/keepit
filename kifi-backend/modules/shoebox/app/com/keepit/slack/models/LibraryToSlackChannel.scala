package com.keepit.slack.models

import com.keepit.common.db.{ Id, State, States }
import com.keepit.common.time._
import com.keepit.model.{ Keep, Library }
import org.joda.time.DateTime

case class LibraryToSlackChannel(
  id: Option[Id[LibraryToSlackChannel]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[LibraryToSlackChannel] = LibraryToSlackChannelStates.ACTIVE,
  membershipId: Id[SlackTeamMembership],
  library: Id[Library],
  webhookId: Id[SlackIncomingWebhookInfo],
  status: SlackIntegrationStatus,
  lastProcessedAt: Option[DateTime],
  lastKeepId: Option[Id[Keep]])

object LibraryToSlackChannelStates extends States[LibraryToSlackChannel]
