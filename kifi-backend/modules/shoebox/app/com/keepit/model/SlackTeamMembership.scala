package com.keepit.model

import com.keepit.common.db.{ States, State, Id }
import com.keepit.common.time._
import org.joda.time.DateTime

case class SlackUserId(value: String)
case class SlackUsername(value: String)

case class SlackTeamId(value: String)
case class SlackTeamName(value: String)

case class SlackAuthToken(value: String)

case class SlackScope(value: String)

case class SlackChannel(value: String) // broad sense, can be channel, group or DM

// unique index on (userId, slackUserId, slackTeamId) => should we use that for referential constraints instead of Id[SlackTeamMembership]? Might make some lookups easier
case class SlackTeamMembership(
  id: Option[Id[SlackTeamMembership]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackTeamMembership] = SlackTeamMembershipStates.ACTIVE,
  userId: Id[User],
  slackUserId: SlackUserId,
  slackUsername: SlackUsername, // this can change, should we store it?
  slackTeamId: SlackTeamId,
  slackTeam: SlackTeamName, // this can change, should we store it?
  token: Option[SlackAuthToken], // optional to handle it being revoked
  scope: Set[SlackScope])

object SlackTeamMembershipStates extends States[SlackTeamMembership]
