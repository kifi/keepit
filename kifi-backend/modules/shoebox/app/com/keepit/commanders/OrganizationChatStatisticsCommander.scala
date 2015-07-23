package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.GroupThreadStats
import com.keepit.model.{ User, Organization, OrganizationRepo }

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class OrganizationChatStatisticsCommander @Inject() (
    val orgRepo: OrganizationRepo,
    val elizaServiceClient: ElizaServiceClient,
    implicit val executionContext: ExecutionContext) {

  trait EngagementStat {

    private def sumUsers(stats: Seq[GroupThreadStats]) =
      stats.map {
        case GroupThreadStats(_, _, numUsers) => numUsers
      }.sum

    def summary(userIds: Set[Id[User]]): Future[Int] =
      detailed(userIds).map { stats =>
        sumUsers(stats)
      }

    def detailed(userIds: Set[Id[User]]): Future[Seq[GroupThreadStats]]

    def summaryBy[A](fn: GroupThreadStats => A)(userIds: Set[Id[User]]): Future[Map[A, Int]] =
      detailed(userIds).map { stats =>
        stats.groupBy(fn).mapValues(sumUsers)
      }

  }

  val internalChats = new EngagementStat {

    override def detailed(userIds: Set[Id[User]]): Future[Seq[GroupThreadStats]] =
      elizaServiceClient.getSharedThreadsForGroupByWeek(userIds.toSeq)

  }

  val allChats = new EngagementStat {

    override def detailed(userIds: Set[Id[User]]): Future[Seq[GroupThreadStats]] =
      elizaServiceClient.getAllThreadsForGroupByWeek(userIds.toSeq)

  }

}

object OrganizationChatStatisticsCommander {

  case class SummaryByYearWeek(year: Int, week: Int, numUsers: Int)

  implicit val summaryByYearWeekOrdering: Ordering[SummaryByYearWeek] = Ordering.by {
    case SummaryByYearWeek(year, week, _) => (year, week)
  }

}
