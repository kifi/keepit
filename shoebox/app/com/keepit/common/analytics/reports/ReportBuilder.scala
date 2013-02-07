package com.keepit.common.analytics.reports


import play.api.Play.current
import play.api.Plugin
import com.keepit.model.EmailAddress
import play.api.templates.Html
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.libs.ws._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError}
import com.keepit.inject._
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.actor.Props
import akka.util.duration._
import akka.actor.ActorRef
import akka.actor.Cancellable
import com.google.inject.Provider
import play.api.libs.concurrent.Promise
import com.google.inject.Inject
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.analytics.reports.Reports.ReportGroup

object Reports {
  lazy val dailyActiveUniqueUserReport = new DailyActiveUniqueUserReport
  lazy val dailyPageLoadReport = new DailyPageLoadReport
  lazy val dailySearchQueriesReport = new DailySearchQueriesReport
  lazy val dailyGoogleResultClicked = new DailyGoogleResultClicked
  lazy val dailyKifiResultClicked = new DailyKifiResultClicked
  lazy val dailyGoogleResultClickedOverKifi = new DailyGoogleResultClickedOverKifi
  lazy val dailySliderShownByAuto = new DailySliderShownByAuto
  lazy val dailySliderShownByIcon = new DailySliderShownByIcon
  lazy val dailySliderShownByKey = new DailySliderShownByKey
  lazy val dailySliderClosedByIcon = new DailySliderClosedByIcon
  lazy val dailySliderClosedByKey = new DailySliderClosedByKey
  lazy val dailySliderClosedByX = new DailySliderClosedByX
  lazy val dailyComment = new DailyComment
  lazy val dailyMessage = new DailyMessage
  lazy val dailyUnkeep = new DailyUnkeep
  lazy val dailyKeep = new DailyKeep
  lazy val dailyUniqueDepricatedAddBookmarks = new DailyUniqueDepricatedAddBookmarks
  lazy val dailyUsefulPage = new DailyUsefulPage

  case class ReportGroup(name: String, reports: Seq[Report])

  lazy val DailyReports = ReportGroup("DailyReport",
    Seq(dailyActiveUniqueUserReport,
      dailyPageLoadReport,
      dailySearchQueriesReport,
      dailyGoogleResultClicked,
      dailyKifiResultClicked,
      dailyGoogleResultClickedOverKifi,
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
      dailyUsefulPage)
  )

  lazy val DailyAdminReports = ReportGroup("DailyAdminReport",
    Seq(dailyUniqueDepricatedAddBookmarks, dailySearchQueriesReport)
  )
}

trait ReportBuilderPlugin extends Plugin {
  def buildReport(startDate: DateTime, endDate: DateTime, report: Report) : Unit
  def buildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup): Unit
  def reportCron(): Unit

  val defaultStartTime = currentDate.minusDays(30).toDateTimeAtStartOfDay
  val defaultEndTime = currentDate.plusDays(1).toDateTimeAtStartOfDay
}

class ReportBuilderPluginImpl @Inject() (system: ActorSystem)
  extends Logging with ReportBuilderPlugin {


  def buildReport(startDate: DateTime, endDate: DateTime, report: Report): Unit = actor ! BuildReport(startDate, endDate, report)
  def buildReports(startDate: DateTime, endDate: DateTime, reportGroup: ReportGroup): Unit = actor ! BuildReports(startDate, endDate, reportGroup)

  private val actor = system.actorOf(Props { new ReportBuilderActor() })
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(10 seconds, 1 hour, actor, ReportCron(this))
    )
  }

  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }

  override def reportCron(): Unit = {
    if (currentDateTime.hourOfDay().get() == 3) // 3am PST
      actor ! BuildReports(defaultStartTime, defaultEndTime, Reports.DailyReports)
  }
}

private[reports] case class ReportCron(sender: ReportBuilderPlugin)
private[reports] case class BuildReport(startDate: DateTime, endDate: DateTime, report: Report)
private[reports] case class BuildReports(startDate: DateTime, endDate: DateTime, reportGroup: Reports.ReportGroup)

private[reports] class ReportBuilderActor() extends Actor with Logging {

  def receive() = {
    case ReportCron(sender) =>
      sender.reportCron()
    case BuildReport(startDate, endDate, report) =>
      report.get(startDate, endDate).persist
    case BuildReports(startDate, endDate, reportGroup) =>
      val builtReports = reportGroup.reports map { report =>
        report.get(startDate, endDate)
      }

      val outputReport = builtReports.foldRight(CompleteReport("","",Nil))((a,b) => a + b)
      outputReport.copy(reportName = reportGroup.name).persist
    case unknown =>
      throw new Exception("unknown message: %s".format(unknown))
  }
}
