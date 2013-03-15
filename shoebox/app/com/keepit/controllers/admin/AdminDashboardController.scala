package com.keepit.controllers.admin

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import play.api.libs.concurrent.Execution.Implicits._
import java.util.concurrent.TimeUnit
import com.keepit.common.db._

import com.keepit.scraper.ScraperPlugin
import com.keepit.search.ArticleStore
import com.keepit.common.controller.AdminController
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.healthcheck.BabysitterTimeout
import org.joda.time.LocalDate
import org.joda.time.DateTimeZone
import org.joda.time.Days
import play.api.libs.json.JsNumber
import play.api.http.ContentTypes
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.Repo
import scala.concurrent.duration._
import views.html

import com.keepit.common.controller.AdminController
import com.google.inject.{Inject, Singleton, Provider}

@Singleton
class AdminDashboardController @Inject() (
  db: Database,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  date: Provider[LocalDate])
    extends AdminController {

  implicit val timeout = BabysitterTimeout(1 minutes, 2 minutes)

  private lazy val userCountByDate = calcCountByDate(db.readOnly(implicit session => userRepo.all).map(_.createdAt.toLocalDateInZone))
  private lazy val bookmarkCountByDate = calcCountByDate(db.readOnly(implicit session => bookmarkRepo.all).map(_.createdAt.toLocalDateInZone))

  private def calcCountByDate(dates: => Seq[LocalDate]) = {
    val day0 = if(dates.isEmpty) currentDate else dates.min
    val dayCounts = dates.foldLeft(Map[LocalDate,Int]().withDefaultValue(0)){(m, d) => m + (d -> (1 + m(d)))}
    val userCounts = if (Play.isDev) {
      Seq.fill(40)(math.round(math.pow((math.random * 4), 2D).toFloat) - 2)
    } else {
      (0 to Days.daysBetween(day0, date.get).getDays()) map {i => dayCounts(day0.plusDays(i))}
    }
    JsObject(List(
        "day0" -> day0.toJson,
        "counts" -> JsArray(userCounts.map {i => JsNumber(i)})
    ))
  }

  def index = AdminHtmlAction { implicit request =>
    Ok(html.admin.adminDashboard())
  }

  def usersByDate = AdminJsonAction { implicit request =>
    Ok(userCountByDate)
  }

  def bookmarksByDate = AdminJsonAction { implicit request =>
    Ok(bookmarkCountByDate)
  }

}
