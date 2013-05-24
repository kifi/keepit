package com.keepit.controllers.admin

import scala.collection.mutable
import org.joda.time.{Months, ReadablePeriod, Weeks}
import com.google.inject.{Inject, Singleton}
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.SearchConfigManager
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json._
import play.api.mvc._
import views.html
import com.keepit.search.SearchConfigExperimentRepo

case class ActivityData(
  numUsers: Int,
  activeUsers1Mo: Set[User],
  inactiveUsers1Mo: Set[User],
  keeping1Mo: Int,
  commenting1Mo: Int,
  activeUsers1Wk: Set[User],
  inactiveUsers1Wk: Set[User],
  keeping1Wk: Int,
  commenting1Wk: Int
)

@Singleton
class AdminEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo,
  userExperimentRepo: UserExperimentRepo,
  emailRepo: EmailAddressRepo,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  rb: ReportBuilderPlugin,
  reportStore: ReportStore,
  events: EventStream,
  activities: ActivityStream,
  activeUsersReports: ActiveUsersReports,
  dailyReports: DailyReports,
  dailyAdminReports: DailyAdminReports,
  reportBuilderPlugin: ReportBuilderPlugin,
  dailySearchStatisticsReports: DailySearchStatisticsReports)
    extends AdminController(actionAuthenticator) {

  def buildReport() = AdminHtmlAction { request =>

    implicit val playrequest = request.request
    val reportForm = Form(
        "reportName" -> text
    )

    val reportGroup = reportForm.bindFromRequest.get.toLowerCase match {
      case "active_users" => activeUsersReports
      case "daily" => dailyReports
      case "admin" => dailyAdminReports
      case "experiment" =>
        val activeExperiments = db.readOnly { implicit s => searchConfigExperimentRepo.getActive() }
        reportBuilderPlugin.searchExperimentReports(activeExperiments)
      case "daily_search_statisitcs" => dailySearchStatisticsReports
      case unknown => throw new Exception("Unknown report: %s".format(unknown))
    }
    rb.buildReports(rb.defaultStartTime, rb.defaultEndTime, reportGroup)
    Redirect(com.keepit.controllers.admin.routes.AdminEventController.reportList())
  }

  def getReport(reportName: String) = AdminCsvAction(reportName + ".csv") { request =>
    log.info(reportName)
    val report = reportStore.get(reportName).get
    Ok(report.toCSV)
  }

  private def getIncludedUsers(): Seq[User] = {
    val excludedExperiments = Seq(ExperimentTypes.BLOCK, ExperimentTypes.FAKE, ExperimentTypes.INACTIVE)
    db.readOnly { implicit s =>
      userRepo.all()
        .filter(_.state == UserStates.ACTIVE)
        .filterNot(u => userExperimentRepo.getUserExperiments(u.id.get).exists(excludedExperiments.contains))
    }
  }

  private def getActivityData(): ActivityData = {
    def queryUserIds(table: String, ago: ReadablePeriod)
        (implicit s: RSession): Set[Id[User]] = {
      val ids = new mutable.ArrayBuffer[Long]()
      val date = currentDate.minus(ago)
      val query = s"SELECT DISTINCT u.id FROM user u, $table t WHERE t.user_id = u.id AND t.created_at > '$date';"
      val rs = s.getPreparedStatement(query).executeQuery()
      while (rs.next()) {
        ids += rs.getLong("id")
      }
      ids.map(Id[User](_)).toSet
    }

    // TODO: stop doing this when we have a large number of users
    val userMap: Map[Id[User], User] =
      getIncludedUsers().map(user => user.id.get -> user).toMap
    val userIds = userMap.keys.toSet
    val (activeUsers1Wk, inactiveUsers1Wk, b1w, c1w) = db.readOnly { implicit session =>
      val b = queryUserIds("bookmark", Weeks.ONE).filter(userIds.contains)
      val c = queryUserIds("comment", Weeks.ONE).filter(userIds.contains)
      val active = (b ++ c).map(userMap.get(_).get)
      val notActive = userMap.values.toSet -- active
      (active, notActive, b.size, c.size)
    }
    val (activeUsers1Mo, inactiveUsers1Mo, b1m, c1m) = db.readOnly { implicit session=>
      val b = queryUserIds("bookmark", Months.ONE).filter(userIds.contains)
      val c = queryUserIds("comment", Months.ONE).filter(userIds.contains)
      val active = (b ++ c).map(userMap.get(_).get)
      val notActive = userMap.values.toSet -- active
      (active, notActive, b.size, c.size)
    }

    ActivityData(userMap.size, activeUsers1Mo, inactiveUsers1Mo, b1m, c1m, activeUsers1Wk, inactiveUsers1Wk, b1w, c1w)
  }

  def activityDataAsCsv() = AdminCsvAction("user_activity_data.csv") { request =>
    val activityData = getActivityData()
    val header = Seq("Name", "Email", "active past 7 days", "active past 30 days").mkString(",")
    val includedUsers = getIncludedUsers()
    val users = db.readOnly { implicit s =>
      includedUsers.map { u => (u, emailRepo.getByUser(u.id.get)) }
    }.toSeq.sortBy(u => s"${u._1.lastName}, ${u._1.firstName}")
    val csvString = header + users.map { case (user, emails) =>
      Seq(s"${user.firstName} ${user.lastName}", emails.map(_.address).mkString("; "),
        if (activityData.activeUsers1Wk.contains(user)) "yes" else "no",
        if (activityData.activeUsers1Mo.contains(user)) "yes" else "no"
      ).map(_.replace("\"", "\"\"")).mkString("\"","\",\"", "\"")
    }.mkString("\n", "\n", "\n")
    Ok(csvString)
  }

  def reportList() = AdminHtmlAction { request =>
    val availableReports = reportStore.getReports() // strip ".json"
    val activityData = getActivityData()
    Ok(html.admin.reports(availableReports, activityData))
  }

  def activityViewer() = AdminHtmlAction { implicit request =>
    Ok(html.admin.adminRTActivityViewer())
  }

  def activityStream() = WebSocket.async[JsValue] { implicit request  =>
    activities.newStream()
  }

  def eventViewer() = AdminHtmlAction { implicit request =>
    Ok(html.admin.adminRTEventViewer())
  }

  def eventStream() = WebSocket.async[JsValue] { implicit request  =>
    events.newStream()
  }
}
