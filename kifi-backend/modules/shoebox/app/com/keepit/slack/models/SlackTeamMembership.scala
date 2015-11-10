package com.keepit.slack.models

import com.keepit.common.db.{ Id, State, States }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime

case class SlackTeamMembership(
  id: Option[Id[SlackTeamMembership]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackTeamMembership] = SlackTeamMembershipStates.ACTIVE,
  userId: Id[User],
  slackUserId: SlackUserId,
  slackUsername: SlackUsername,
  slackTeamId: SlackTeamId,
  slackTeam: SlackTeamName,
  token: Option[SlackAccessToken],
  scope: Set[SlackAuthScope])

object SlackTeamMembershipStates extends States[SlackTeamMembership]
