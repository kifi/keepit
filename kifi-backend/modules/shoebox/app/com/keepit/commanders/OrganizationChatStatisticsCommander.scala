package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.GroupThreadStats
import com.keepit.model.{ User, Organization, OrganizationRepo }

import scala.concurrent.{ ExecutionContext, Future }
import play.api.libs.json._
import play.api.libs.functional.syntax._

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

case class SummaryByYearWeek(year: Int, week: Int, numUsers: Int = 0)

object SummaryByYearWeek {

  val extractYearWeek: SummaryByYearWeek => (Int, Int) = {
    case SummaryByYearWeek(year, week, _) => (year, week)
  }

  implicit val summaryByYearWeekOrdering: Ordering[SummaryByYearWeek] = Ordering.by(extractYearWeek)

  implicit val summaryByYearWeekDataWrites: Writes[SummaryByYearWeek] = Writes {
    case SummaryByYearWeek(_, _, numUsers) => Json.toJson(numUsers)
  }

  implicit val summaryByYearWeekDataSeqWrites = Writes.seq(summaryByYearWeekDataWrites)

  implicit val summaryByYearWeekHumanWrites: Writes[SummaryByYearWeek] = Writes {
    case SummaryByYearWeek(year, week, _) => Json.toJson(s"$year week $week")
  }

  implicit val summaryByYearHumanSeqWrites = Writes.seq(summaryByYearWeekHumanWrites)

  def fillInMissing(min: SummaryByYearWeek, max: SummaryByYearWeek)(stats: Seq[SummaryByYearWeek]): Seq[SummaryByYearWeek] = {
    val statsByYearWeekPair = stats.map(stat => extractYearWeek(stat) -> stat).toMap
    val allEmptyStats = (min.year to max.year).flatMap { year =>
      (if (year == min.year) min.week else 1) to (if (year == max.year) max.week else 52) map { week =>
        SummaryByYearWeek(year, week)
      }
    }
    allEmptyStats.map { emptyStat =>
      statsByYearWeekPair.get(extractYearWeek(emptyStat)) match {
        case Some(filledStat) => filledStat
        case None => emptyStat
      }
    }
  }

}

