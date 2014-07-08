package com.keepit.controllers.admin

import scala.concurrent.duration._

import org.joda.time.Days
import org.joda.time.LocalDate

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.BabysitterTimeout
import com.keepit.common.time._
import com.keepit.model._

import play.api.Play.current
import play.api._
import play.api.libs.json.Json
import views.html

//this is a singleton, bad pattern but the code here will no scale and soon it will be refactored anyway
@Singleton
class AdminDashboardController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo,
  clock: Clock)
    extends AdminController(actionAuthenticator) {

  implicit val dbMasterSlave = Database.Slave

  implicit val timeout = BabysitterTimeout(1 minutes, 2 minutes)

  private lazy val userCountByDate = calcCountByDate(db.readOnlyMaster(implicit session => userRepo.allActiveTimes).map(_.toLocalDateInZone))

  private def calcCountByDate(dates: => Seq[LocalDate]) = {
    val day0 = if(dates.isEmpty) currentDate else dates.min
    val dayCounts = dates.foldLeft(Map[LocalDate,Int]().withDefaultValue(0)){(m, d) => m + (d -> (1 + m(d)))}
    val userCounts = if (Play.isDev) {
      Seq.fill(40)(math.round(math.pow((math.random * 4), 2D).toFloat) - 2)
    } else {
      (0 to Days.daysBetween(day0, clock.today).getDays()) map {i => dayCounts(day0.plusDays(i))}
    }
    Json.obj("day0" -> day0, "counts" -> userCounts)
  }

  def index = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.adminDashboard())
  }

  def usersByDate = AdminJsonAction.authenticated { implicit request =>
    Ok(userCountByDate)
  }

}
