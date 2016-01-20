package com.keepit.slack.models

import com.keepit.model.{ BasicOrganization, Library }
import org.joda.time.Period

case class SlackTeamDigest(
    slackTeam: SlackTeam,
    timeSinceLastDigest: Period,
    org: BasicOrganization,
    ingestedLinksByChannel: Map[SlackChannelId, Set[String]],
    librariesByChannel: Map[SlackChannelId, Set[Library]]) {
  lazy val numIngestedLinksByChannel = ingestedLinksByChannel.mapValues(_.size)
  lazy val numIngestedLinks = numIngestedLinksByChannel.values.sum

  lazy val channelsByLibrary = librariesByChannel.values.flatten.map { lib =>
    lib -> librariesByChannel.filter { case (channelId, libs) => libs.contains(lib) }.keySet
  }.toMap
  lazy val numIngestedLinksByLibrary = channelsByLibrary.map {
    case (lib, channels) => lib -> channels.flatMap(numIngestedLinksByChannel.get).sum
  }
}

case class SlackChannelDigest(
    slackChannel: SlackChannel,
    timeSinceLastDigest: Period,
    ingestedLinks: Set[String],
    libraries: Seq[Library]) {
  lazy val numIngestedLinks = ingestedLinks.size
}
