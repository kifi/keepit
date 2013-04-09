package com.keepit.common.analytics.reports

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import org.joda.time.DateTime

import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.actor.ActorFactory
import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.analytics.reports.Reports.ReportGroup
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.{SearchConfigManager, SearchConfigExperiment}

import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import com.keepit.common.plugin.SchedulingPlugin

object Reports {
  lazy val dailyActiveUniqueUserReport = new DailyActiveUniqueUserReportRepo
  lazy val dailyPageLoadReport = new DailyPageLoadReportRepo
  lazy val dailySearchQueriesReport = new DailySearchQueriesReportRepo
  lazy val dailyGoogleResultClicked = new DailyGoogleResultClickedRepo
  lazy val dailyKifiResultClicked = new DailyKifiResultClickedRepo
  lazy val dailyKifiAtLeastOneResult = new DailyKifiAtLeastOneResultRepo
  lazy val dailySliderShownByAuto = new DailySliderShownByAutoRepo
  lazy val dailySliderShownByIcon = new DailySliderShownByIconRepo
  lazy val dailySliderShownByKey = new DailySliderShownByKeyRepo
  lazy val dailySliderClosedByIcon = new DailySliderClosedByIconRepo
  lazy val dailySliderClosedByKey = new DailySliderClosedByKeyRepo
  lazy val dailySliderClosedByX = new DailySliderClosedByXRepo
  lazy val dailyComment = new DailyCommentRepo
  lazy val dailyMessage = new DailyMessageRepo
  lazy val dailyUnkeep = new DailyUnkeepRepo
  lazy val dailyKeep = new DailyKeepRepo
  lazy val dailyUniqueDepricatedAddBookmarks = new DailyUniqueDepricatedAddBookmarksRepo
  lazy val dailyUsefulPage = new DailyUsefulPageRepo
  lazy val dailyTotalUsers = new DailyTotalUsersRepo
  lazy val dailyPrivateKeeps = new DailyPrivateKeepsRepo
  lazy val dailyPublicKeeps = new DailyPublicKeepsRepo
  lazy val dailyNewThread = new DailyNewThreadRepo
  lazy val dailyUniqueUsersKeeping = new DailyUniqueUsersKeepingRepo
  lazy val dailyUniqueUsersMessaging = new DailyUniqueUsersMessagingRepo
  lazy val dailyUniqueUsersCommenting = new DailyUniqueUsersCommentingRepo
  lazy val dailyKifiLoadedReport = new DailyKifiLoadedReportRepo
  lazy val dailyDustSettledKifiHadResults = new DailyDustSettledKifiHadResultsRepo(true)
  lazy val dailyDustSettledKifiHadNoResults = new DailyDustSettledKifiHadResultsRepo(false)
  lazy val dailyActiveUsers = new DailyActiveUsersRepo
  lazy val weeklyActiveUsers = new WeeklyActiveUsersRepo
  lazy val monthlyActiveUsers = new MonthlyActiveUsersRepo
  lazy val dailyClickingUsers = new DailyClickingUsersRepo
  lazy val weeklyClickingUsers = new WeeklyClickingUsersRepo
  lazy val monthlyClickingUsers = new MonthlyClickingUsersRepo
  lazy val dailyKeepingUsers = new DailyKeepingUsersRepo
  lazy val weeklyKeepingUsers = new WeeklyKeepingUsersRepo
  lazy val monthlyKeepingUsers = new MonthlyKeepingUsersRepo
  lazy val dailyKCMUsers = new DailyKCMUsersRepo
  lazy val weeklyKCMUsers = new WeeklyKCMUsersRepo
  lazy val monthlyKCMUsers = new MonthlyKCMUsersRepo
  lazy val dailySearchStatstics = new DailySearchStatisticsReportRepo

  case class ReportGroup(name: String, reports: Seq[ReportRepo])

  lazy val DailyReports = ReportGroup("DailyReport",
    Seq(dailyActiveUniqueUserReport,
      dailyPageLoadReport,
      dailySearchQueriesReport,
      dailyGoogleResultClicked,
      dailyKifiResultClicked,
      dailyKifiAtLeastOneResult,
      dailySliderShownByAuto,
      dailySliderShownByIcon,
      dailySliderShownByKey,
      dailySliderClosedByIcon,
      dailySliderClosedByKey,
      dailySliderClosedByX,
      dailyComment,
      dailyMessage,
      dailyUnkeep,
      dailyKeep,
      dailyUsefulPage,
      dailyTotalUsers,
      dailyTotalUsers,
      dailyPrivateKeeps,
      dailyPublicKeeps,
      dailyNewThread,
      dailyUniqueUsersKeeping,
      dailyUniqueUsersMessaging,
      dailyUniqueUsersCommenting,
      dailyKifiLoadedReport,
      dailyDustSettledKifiHadResults,
      dailyDustSettledKifiHadNoResults)
  )

  lazy val ActiveUsersReports = ReportGroup("ActiveUsersReport",
    Seq(
      dailyActiveUsers, weeklyActiveUsers, monthlyActiveUsers,
      dailyClickingUsers, weeklyClickingUsers, monthlyClickingUsers,
      dailyKCMUsers, weeklyKCMUsers, monthlyKCMUsers,
      dailyKeepingUsers, weeklyKeepingUsers, monthlyKeepingUsers
    )
  )

  lazy val DailyAdminReports = ReportGroup("DailyAdminReport",
    Seq(dailyUniqueDepricatedAddBookmarks, dailySearchQueriesReport)
  )

  lazy val DailySearchStatisticsReports = ReportGroup("DailySearchStatistics",
    Seq(dailySearchStatstics)
  )

  def searchExperimentReports(experiments: Seq[SearchConfigExperiment]): ReportGroup = {
    val constructors = Seq(
      new DailyKifiResultClickedByExperimentRepo(_),
      new DailyGoogleResultClickedByExperimentRepo(_),
      new DailyKifiAtLeastOneResultByExperimentRepo(_),
      (x: Option[SearchConfigExperiment]) => new DailyDustSettledKifiHadResultsByExperimentRepo(x, true),
      (x: Option[SearchConfigExperiment]) => new DailyDustSettledKifiHadResultsByExperimentRepo(x, false))
    ReportGroup("SearchExperimentReport",
      for (experiment <- experiments; constructor <- constructors) yield constructor(Some(experiment))
    )
  }
}

trait ReportBuilderPlugin extends SchedulingPlugin {
  def buildReport(startDate: DateTime, endDate: DateTime, report: ReportRepo) : Unit
  def buildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup): Unit
  def reportCron(): Unit

  def defaultStartTime = currentDate.minusDays(30).toDateTimeAtStartOfDay
  def defaultEndTime = currentDate.plusDays(1).toDateTimeAtStartOfDay
}

class ReportBuilderPluginImpl @Inject() (
  actorFactory: ActorFactory[ReportBuilderActor],
  searchConfigManager: SearchConfigManager,
  reportStore: ReportStore)
    extends Logging with ReportBuilderPlugin {

  def buildReport(startDate: DateTime, endDate: DateTime, report: ReportRepo): Unit = actor ! BuildReport(startDate, endDate, report)
  def buildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup): Unit = actor ! BuildReports(startDate, endDate, reportGroup)

  private lazy val actor = actorFactory.get()
  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actorFactory.system, 10 seconds, 1 hour, actor, ReportCron(this))
  }

  override def reportCron(): Unit = {
    if (currentDateTime.hourOfDay().get() == 3) {// 3am PST
      actor ! BuildReports(defaultStartTime, defaultEndTime,
        Reports.searchExperimentReports(searchConfigManager.activeExperiments))
      actor ! BuildReports(defaultStartTime, defaultEndTime, Reports.DailyReports)
    }
  }
}

private[reports] case class ReportCron(sender: ReportBuilderPlugin)
private[reports] case class BuildReport(startDate: DateTime, endDate: DateTime, report: ReportRepo)
private[reports] case class BuildReports(startDate: DateTime, endDate: DateTime, reportGroup: Reports.ReportGroup)

private[reports] class ReportBuilderActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    reportStore: ReportStore)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case ReportCron(sender) =>
      sender.reportCron()
    case BuildReport(startDate, endDate, report) =>
      val toPersist = report.get(startDate, endDate)
      reportStore += (toPersist.persistenceKey -> toPersist)
    case BuildReports(startDate, endDate, reportGroup) =>
      val builtReports = reportGroup.reports map { report =>
        report.get(startDate, endDate)
      }

      val outputReport = builtReports.foldRight(Report("","",Nil))((a,b) => a + b)
      val report = outputReport.copy(reportName = reportGroup.name)
      reportStore += (report.persistenceKey -> report)
    case unknown =>
      throw new Exception("unknown message: %s".format(unknown))
  }
}
