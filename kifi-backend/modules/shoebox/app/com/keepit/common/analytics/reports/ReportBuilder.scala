package com.keepit.common.analytics.reports

import play.api.Plugin
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import org.joda.time.DateTime
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.google.inject.Inject
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.{SearchConfigExperiment}
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.analytics.MongoEventStore
import com.keepit.search.SearchConfigExperimentRepo
import com.keepit.common.db.slick.Database
import us.theatr.akka.quartz.QuartzActor

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


trait ReportBuilderPlugin extends Plugin {
  def buildReport(startDate: DateTime, endDate: DateTime, report: ReportRepo) : Unit
  def buildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup): Unit
  def reportCron(): Unit

  def defaultStartTime = currentDate.minusDays(30).toDateTimeAtStartOfDay
  def defaultEndTime = currentDate.plusDays(1).toDateTimeAtStartOfDay

  def searchExperimentReports(experiments: Seq[SearchConfigExperiment]): ReportGroup
}

class ReportBuilderPluginImpl @Inject() (
  actor: ActorInstance[ReportBuilderActor],
  quartz: ActorInstance[QuartzActor],
  db: Database,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  reportStore: ReportStore,
  store: MongoEventStore,
  dailyReports: DailyReports,
  val schedulingProperties: SchedulingProperties) //only on leader
    extends Logging with ReportBuilderPlugin with SchedulingPlugin {

  def buildReport(startDate: DateTime, endDate: DateTime, report: ReportRepo): Unit = actor.ref ! BuildReport(startDate, endDate, report)
  def buildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup): Unit = actor.ref ! BuildReports(startDate, endDate, reportGroup)

  implicit val dbMasterSlave = Database.Slave

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    cronTask(quartz, actor.ref, "0 0 3 * * ?", searchExperimentReportsEvent)
    cronTask(quartz, actor.ref, "0 0 3 * * ?", dailyReportsEvent)
  }

  private val dailyReportsEvent = BuildReports(defaultStartTime, defaultEndTime, dailyReports)
  private val searchExperimentReportsEvent = BuildReports(defaultStartTime, defaultEndTime,
         searchExperimentReports(db.readOnly { implicit s => searchConfigExperimentRepo.getActive() }))

  def reportCron() {
    actor.ref ! dailyReportsEvent
    actor.ref ! searchExperimentReportsEvent
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

private[reports] case class BuildReport(startDate: DateTime, endDate: DateTime, report: ReportRepo)
private[reports] case class BuildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup)

private[reports] class ReportBuilderActor @Inject() (
    airbrake: AirbrakeNotifier,
    reportStore: ReportStore)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
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
    case m => throw new UnsupportedActorMessage(m)
  }
}
