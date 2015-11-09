package com.keepit.model

import com.keepit.common.db.{ States, State, Id }
import org.joda.time.DateTime
import com.keepit.common.time._

case class SlackChannelToLibrary(
  id: Option[Id[SlackChannelToLibrary]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackChannelToLibrary] = SlackChannelToLibraryStates.ACTIVE,
  ownerId: Id[SlackTeamMembership],
  channel: SlackChannel,
  library: Id[Library],
  status: SlackIntegrationStatus,
  lastProcessedAt: Option[DateTime],
  lastMessageAt: Option[DateTime])

object SlackChannelToLibraryStates extends States[SlackChannelToLibrary]
