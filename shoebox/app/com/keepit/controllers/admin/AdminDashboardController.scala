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
import akka.util.duration._
import java.util.concurrent.TimeUnit
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.controllers.CommonActions._
import com.keepit.inject._
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.ArticleStore
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.healthcheck.BabysitterTimeout
import org.joda.time.LocalDate
import org.joda.time.DateTimeZone
import org.joda.time.Days
import play.api.libs.json.JsNumber
import play.api.http.ContentTypes
import com.keepit.common.db.slick.DBConnection
import com.keepit.common.db.slick.Repo

/**
 * Charts, etc.
 */
object AdminDashboardController extends FortyTwoController {

  implicit val timeout = BabysitterTimeout(1 minutes, 2 minutes)

  private lazy val userCountByDate = calcCountByDate(inject[DBConnection].readOnly(implicit session => inject[UserRepo].all).map(_.createdAt.toLocalDateInZone))
  private lazy val bookmarkCountByDate = calcCountByDate(inject[DBConnection].readOnly(implicit session => inject[BookmarkRepo].all).map(_.createdAt.toLocalDateInZone))

  private def calcCountByDate(dates: => Seq[LocalDate]) = {

    val day0 = if(dates.isEmpty) currentDate else dates.min
    val dayCounts = dates.foldLeft(Map[LocalDate,Int]().withDefaultValue(0)){(m, d) => m + (d -> (1 + m(d)))}
    val userCounts = if (Play.isDev) {
      Seq.fill(40)(math.round(math.pow((math.random * 4), 2D).toFloat) - 2)
    } else {
      (0 to Days.daysBetween(day0, inject[LocalDate]).getDays()) map {i => dayCounts(day0.plusDays(i))}
    }
    JsObject(List(
        "day0" -> day0.toJson,
        "counts" -> JsArray(userCounts.map {i => JsNumber(i)})
    ))
  }

  def index = AdminHtmlAction { implicit request =>
    Ok(views.html.adminDashboard())
  }

  def usersByDate = AdminJsonAction { implicit request =>
    Ok(userCountByDate)
  }

  def bookmarksByDate = AdminJsonAction { implicit request =>
    Ok(bookmarkCountByDate)
  }

}
