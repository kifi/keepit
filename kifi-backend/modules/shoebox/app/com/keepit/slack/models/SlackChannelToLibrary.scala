package com.keepit.slack.models

import com.keepit.common.db.{ Id, State, States }
import com.keepit.common.time._
import com.keepit.model.Library
import org.joda.time.DateTime

case class SlackChannelToLibrary(
  id: Option[Id[SlackChannelToLibrary]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackChannelToLibrary] = SlackChannelToLibraryStates.ACTIVE,
  membershipId: Id[SlackTeamMembership],
  channel: SlackChannel,
  library: Id[Library],
  status: SlackIntegrationStatus,
  lastProcessedAt: Option[DateTime],
  lastMessageAt: Option[DateTime])

object SlackChannelToLibraryStates extends States[SlackChannelToLibrary]
