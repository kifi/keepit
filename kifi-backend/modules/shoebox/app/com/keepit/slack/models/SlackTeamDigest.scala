package com.keepit.slack.models

import com.keepit.model.{ BasicOrganization, Library }

case class SlackTeamDigest(
    slackTeam: SlackTeam,
    org: BasicOrganization,
    numIngestedKeepsByLibrary: Map[Library, Int]) {
  def numIngestedKeeps = numIngestedKeepsByLibrary.values.sum
}

