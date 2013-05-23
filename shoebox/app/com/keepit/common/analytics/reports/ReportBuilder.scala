package com.keepit.common.analytics.reports

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import org.joda.time.DateTime
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.actor.ActorFactory
import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.{SearchConfigExperiment}
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.analytics.MongoEventStore
import com.keepit.search.SearchConfigExperimentRepo
import com.keepit.common.db.slick.Database

class ReportGroup(val name: String, val reports: Seq[ReportRepo])

class DailyReports @Inject() (dailyActiveUniqueUserReport: DailyActiveUniqueUserReportRepo,
    dailyPageLoadReport: DailyPageLoadReportRepo,
    dailySearchQueriesReport: DailySearchQueriesReportRepo,
    dailyGoogleResultClicked: DailyGoogleResultClickedRepo,
    dailyKifiResultClicked: DailyKifiResultClickedRepo,
    dailyKifiAtLeastOneResult: DailyKifiAtLeastOneResultRepo,
    dailySliderShownByAuto: DailySliderShownByAutoRepo,
    dailySliderShownByIcon: DailySliderShownByIconRepo,
    dailySliderShownByKey: DailySliderShownByKeyRepo,
    dailySliderClosedByIcon: DailySliderClosedByIconRepo,
    dailySliderClosedByKey: DailySliderClosedByKeyRepo,
    dailySliderClosedByX: DailySliderClosedByXRepo,
    dailyComment: DailyCommentRepo,
    dailyMessage: DailyMessageRepo,
    dailyUnkeep: DailyUnkeepRepo,
    dailyKeep: DailyKeepRepo,
    dailyUsefulPage: DailyUsefulPageRepo,
    dailyTotalUsers: DailyTotalUsersRepo,
    dailyPrivateKeeps: DailyPrivateKeepsRepo,
    dailyPublicKeeps: DailyPublicKeepsRepo,
    dailyNewThread: DailyNewThreadRepo,
    dailyUniqueUsersKeeping: DailyUniqueUsersKeepingRepo,
    dailyUniqueUsersMessaging: DailyUniqueUsersMessagingRepo,
    dailyUniqueUsersCommenting: DailyUniqueUsersCommentingRepo,
    dailyKifiLoadedReport: DailyKifiLoadedReportRepo,
    dailyDustSettledKifiHadResults: DailyDustSettledKifiHadResultsRepo,
    dailyDustSettledKifiHadNoResults: DailyDustSettledKifiHadNoResultsRepo)
  extends ReportGroup("DailyReport",
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

class ActiveUsersReports @Inject() (
    dailyActiveUsers: DailyActiveUsersRepo,
    weeklyActiveUsers: WeeklyActiveUsersRepo,
    monthlyActiveUsers: MonthlyActiveUsersRepo,
    dailyClickingUsers: DailyClickingUsersRepo,
    weeklyClickingUsers: WeeklyClickingUsersRepo,
    monthlyClickingUsers: MonthlyClickingUsersRepo,
    dailyKCMUsers: DailyKCMUsersRepo,
    weeklyKCMUsers: WeeklyKCMUsersRepo,
    monthlyKCMUsers: MonthlyKCMUsersRepo,
    dailyKeepingUsers: DailyKeepingUsersRepo,
    weeklyKeepingUsers: WeeklyKeepingUsersRepo,
    monthlyKeepingUsers: MonthlyKeepingUsersRepo
    ) extends ReportGroup("ActiveUsersReport",
  Seq(
    dailyActiveUsers, weeklyActiveUsers, monthlyActiveUsers,
    dailyClickingUsers, weeklyClickingUsers, monthlyClickingUsers,
    dailyKCMUsers, weeklyKCMUsers, monthlyKCMUsers,
    dailyKeepingUsers, weeklyKeepingUsers, monthlyKeepingUsers
  )
)

class DailyAdminReports @Inject() (
    dailyUniqueDepricatedAddBookmarks: DailyUniqueDepricatedAddBookmarksRepo,
    dailySearchQueriesReport: DailySearchQueriesReportRepo)
    extends ReportGroup("DailyAdminReport",
  Seq(dailyUniqueDepricatedAddBookmarks, dailySearchQueriesReport)
)

class DailySearchStatisticsReports @Inject() (
    dailySearchStatstics: DailySearchStatisticsReportRepo)
  extends ReportGroup("DailySearchStatistics", Seq(dailySearchStatstics))


trait ReportBuilderPlugin extends SchedulingPlugin {
  def buildReport(startDate: DateTime, endDate: DateTime, report: ReportRepo) : Unit
  def buildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup): Unit
  def reportCron(): Unit

  def defaultStartTime = currentDate.minusDays(30).toDateTimeAtStartOfDay
  def defaultEndTime = currentDate.plusDays(1).toDateTimeAtStartOfDay

  def searchExperimentReports(experiments: Seq[SearchConfigExperiment]): ReportGroup
}

class ReportBuilderPluginImpl @Inject() (
  actorFactory: ActorFactory[ReportBuilderActor],
  db: Database,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  reportStore: ReportStore,
  store: MongoEventStore,
  dailyReports: DailyReports)
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
         searchExperimentReports(db.readOnly { implicit s => searchConfigExperimentRepo.getActive() }))
      actor ! BuildReports(defaultStartTime, defaultEndTime, dailyReports)
    }
  }

  def searchExperimentReports(experiments: Seq[SearchConfigExperiment]): ReportGroup = {
    val constructors = Seq(
      new DailyKifiResultClickedByExperimentRepo(_, _),
      new DailyGoogleResultClickedByExperimentRepo(_, _),
      new DailyKifiAtLeastOneResultByExperimentRepo(_, _),
      (store: MongoEventStore, x: Option[SearchConfigExperiment]) => new DailyDustSettledKifiHadResultsByExperimentRepo(store, x, true),
      (store: MongoEventStore, x: Option[SearchConfigExperiment]) => new DailyDustSettledKifiHadResultsByExperimentRepo(store, x, false))
    new ReportGroup("SearchExperimentReport",
      for (experiment <- experiments; constructor <- constructors) yield constructor(store, Some(experiment))
    )
  }
}

private[reports] case class ReportCron(sender: ReportBuilderPlugin)
private[reports] case class BuildReport(startDate: DateTime, endDate: DateTime, report: ReportRepo)
private[reports] case class BuildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup)

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
