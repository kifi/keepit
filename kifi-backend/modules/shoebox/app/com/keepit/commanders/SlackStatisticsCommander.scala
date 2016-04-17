package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.logging.Logging
import com.keepit.slack.{ SlackClientWrapper, SlackClient }
import com.keepit.slack.models._
import scala.concurrent.{ Future, ExecutionContext }

case class SlackStatistics(
  activeSlackLibs: Int,
  inactiveSlackLibs: Int,
  closedSlackLibs: Int,
  brokenSlackLibs: Int,
  teamSize: Int,
  bots: Set[SlackUsername])

object SlackStatistics {
  def apply(teamSize: Int, bots: Set[SlackUsername], slacking: Iterable[SlackChannelToLibrary]): SlackStatistics = {
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
    slackClient: SlackClientWrapper,
    implicit val executionContext: ExecutionContext) extends Logging {

  def getTeamMembersCount(slackTeamId: SlackTeamId): Future[Int] = {
    val count = slackTeamMembersCountCache.direct.getOrElseFuture(SlackTeamMembersCountKey(slackTeamId)) {
      getTeamMembers(slackTeamId).map(_.filterNot(_.bot).size)
    }
    count.recover {
      case error =>
        log.error(s"error fetching members for $slackTeamId", error)
        -2
    }
  }

  def getSlackBots(slackTeamId: SlackTeamId): Future[Set[SlackUsername]] = {
    slackTeamBotsCache.direct.getOrElseFuture(SlackTeamBotsKey(slackTeamId)) {
      val bots = getTeamMembers(slackTeamId).map(_.filter(_.bot).map(_.username).toSet)
      bots.recover {
        case error =>
          log.error("error fetching members", error)
          Set(SlackUsername("ERROR"))
      }
    }
  }

  def getTeamMembers(slackTeamId: SlackTeamId): Future[Seq[FullSlackUserInfo]] = {
    slackTeamMembersCache.direct.getOrElseFuture(SlackTeamMembersKey(slackTeamId)) {
      slackClient.getUsers(slackTeamId).map { allMembers =>
        val deleted = allMembers.filter(_.deleted)
        val bots = allMembers.filterNot(_.deleted).filter(_.bot)
        log.info(s"fetched members from slack team $slackTeamId: out of ${allMembers.size}, ${deleted.size} deleted, ${bots.size} where bots: ${bots.map(_.username)}")
        allMembers.filterNot(_.deleted)
      }
    }
  }
}
