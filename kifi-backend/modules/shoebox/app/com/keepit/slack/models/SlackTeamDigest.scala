package com.keepit.slack.models

import com.keepit.model.{ BasicOrganization, Library }
import org.joda.time.Period

case class SlackTeamDigest(
    slackTeam: SlackTeam,
    timeSinceLastDigest: Period,
    org: BasicOrganization,
    numIngestedKeepsByLibrary: Map[Library, Int]) {
  def numIngestedKeeps = numIngestedKeepsByLibrary.values.sum
}

