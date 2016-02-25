package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.logging.Logging
import com.keepit.slack.SlackClient
import com.keepit.slack.models._
import scala.concurrent.{ Future, ExecutionContext }

case class SlackStatistics(
  activeSlackLibs: Int,
  inactiveSlackLibs: Int,
  closedSlackLibs: Int,
  brokenSlackLibs: Int,
  teamSize: Int,
  bots: Set[String])

object SlackStatistics {
  def apply(teamSize: Int, bots: Set[String], slacking: Iterable[SlackChannelToLibrary]): SlackStatistics = {
    SlackStatistics(
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.On },
      slacking.count { s => s.state == SlackChannelToLibraryStates.INACTIVE },
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.Off },
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.Broken },
      teamSize, bots
    )
  }
}

class SlackStatisticsCommander @Inject() (
    slackTeamMembersCountCache: SlackTeamMembersCountCache,
    slackTeamMembersCache: SlackTeamMembersCache,
    slackTeamBotsCache: SlackTeamBotsCache,
    slackClient: SlackClient,
    implicit val executionContext: ExecutionContext) extends Logging {

  def getTeamMembersCount(slackTeamMembership: SlackTeamMembership)(implicit session: RSession): Future[Int] = {
    val count = slackTeamMembersCountCache.getOrElseFuture(SlackTeamMembersCountKey(slackTeamMembership.slackTeamId)) {
      getTeamMembers(slackTeamMembership: SlackTeamMembership).map(_.filterNot(_.bot).size)
    }
    count.recover {
      case error =>
        log.error(s"error fetching members with $slackTeamMembership", error)
        -2
    }
  }

  def getSlackBots(slackTeamMembership: SlackTeamMembership)(implicit session: RSession): Future[Set[String]] = {
    slackTeamBotsCache.getOrElseFuture(SlackTeamBotsKey(slackTeamMembership.slackTeamId)) {
      val bots = getTeamMembers(slackTeamMembership).map(_.filter(_.bot).map(_.name.value).toSet)
      bots.recover {
        case error =>
          log.error("error fetching members", error)
          Set("ERROR")
      }
    }
  }

  def getTeamMembers(slackTeamMembership: SlackTeamMembership)(implicit session: RSession): Future[Seq[SlackUserInfo]] = {
    slackTeamMembersCache.getOrElseFuture(SlackTeamMembersKey(slackTeamMembership.slackTeamId)) {
      slackClient.getUsersList(slackTeamMembership.token.get, slackTeamMembership.slackUserId).map { allMembers =>
        val deleted = allMembers.filter(_.deleted)
        val bots = allMembers.filterNot(_.deleted).filter(_.bot)
        log.info(s"fetched members from slack team ${slackTeamMembership.slackTeamName} ${slackTeamMembership.slackTeamId} via user ${slackTeamMembership.slackUsername} ${slackTeamMembership.slackUserId}; " +
          s"out of ${allMembers.size}, ${deleted.size} deleted, ${bots.size} where bots: ${bots.map(_.name)}")
        allMembers.filterNot(_.deleted)
      }
    }
  }

}
