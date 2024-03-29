package com.keepit.slack.models

import com.keepit.common.db.Id
import com.keepit.model.{ Keep, PrettySlackMessage, BasicOrganization, Library }
import org.joda.time.{ Duration, Period }

case class SlackTeamDigest(
    slackTeam: SlackTeam,
    digestPeriod: Duration,
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

case class SlackPersonalDigest(
    slackMembership: SlackTeamMembership,
    slackTeam: SlackTeam,
    allMembers: Set[SlackTeamMembership],
    digestPeriod: Duration,
    org: BasicOrganization,
    ingestedMessagesByChannel: Map[SlackChannelIdAndPrettyName, Seq[(Keep, PrettySlackMessage)]]) {
  lazy val numIngestedMessagesByChannel = ingestedMessagesByChannel.mapValues(_.length)
  lazy val numIngestedMessages = numIngestedMessagesByChannel.values.sum
  lazy val mostRecentMessage = ingestedMessagesByChannel.values.flatten.maxBy { case (k, msg) => (msg.timestamp.value, k.id.get.id) }
}
