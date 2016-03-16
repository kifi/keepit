package com.keepit.slack

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ Organization, User }
import com.keepit.slack.models.{ SlackUserId, SlackTeamId, SlackTeamMembership, SlackTeamName }
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.mvc.Results.Status

sealed abstract class SlackActionFail(val code: String, val msg: String) extends Exception(msg) {
  def bestEffortRedirect: Option[Path] = None
  def asErrorResponse = Status(BAD_REQUEST)(Json.obj("code" -> code))
}
object SlackActionFail {
  case class TeamNotConnected(slackTeamId: SlackTeamId, slackTeamName: SlackTeamName)
    extends SlackActionFail("slack_team_not_connected", s"SlackTeam ${slackTeamName.value} (${slackTeamId.value}) is not connected to any organization.")

  case class TeamAlreadyConnected(slackTeamId: SlackTeamId, slackTeamName: SlackTeamName, connectedOrgId: Id[Organization])
    extends SlackActionFail("slack_team_already_connected", s"SlackTeam ${slackTeamName.value} (${slackTeamId.value}) is already connected to organization $connectedOrgId")

  case class OrgNotConnected(orgId: Id[Organization])
    extends SlackActionFail("kifi_org_not_connected", s"Organization $orgId is not connected to any Slack team.")

  case class OrgAlreadyConnected(orgId: Id[Organization], connectedTeam: SlackTeamId, failedToConnectTeam: SlackTeamId)
    extends SlackActionFail("kifi_org_already_connected", s"Organization $orgId is already connected to $connectedTeam so it cannot connect to $failedToConnectTeam")

  case class TeamNotFound(slackTeamId: SlackTeamId)
    extends SlackActionFail("slack_team_not_found", s"We could not find SlackTeam ${slackTeamId.value}")

  case class InvalidMembership(userId: Id[User], slackTeamId: SlackTeamId, slackTeamName: SlackTeamName, membership: Option[SlackTeamMembership])
    extends SlackActionFail("invalid_slack_membership", s"User $userId is not a valid member of SlackTeam ${slackTeamName.value} (${slackTeamId.value}): $membership")

  case class MembershipAlreadyConnected(userId: Id[User], ownerId: Id[User], slackTeamId: SlackTeamId, slackTeamName: SlackTeamName, slackUserId: SlackUserId, membership: SlackTeamMembership)
    extends SlackActionFail("membership_already_connected", s"$userId is trying to steal existing membership from $ownerId on SlackTeam ${slackTeamName.value} (${slackTeamId.value}): $membership")

  case class MissingWebhook(userId: Id[User])
    extends SlackActionFail("missing_webhook", s"User $userId tried to integrate with a library, but we did not get a webhook from Slack")
}
