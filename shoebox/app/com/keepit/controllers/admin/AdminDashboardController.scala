package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.controllers.CommonActions._
import com.keepit.inject._
import com.keepit.scraper.ScraperPlugin
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.search.ArticleStore
import com.keepit.common.controller.FortyTwoController
import org.joda.time.LocalDate
import org.joda.time.DateTimeZone
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.Days
import play.api.libs.json.JsNumber
import play.api.http.ContentTypes

/**
 * Charts, etc.
 */
object AdminDashboardController extends FortyTwoController {

  private val userCountByDate = calcUserCountByDate

  private def calcUserCountByDate = {
    val dates = CX.withConnection { implicit conn => User.all }.map(_.createdAt.toLocalDateInZone)
    val day0 = dates.min
    val dayCounts = dates.foldLeft(Map[LocalDate,Int]().withDefaultValue(0)){(m, d) => m + (d -> (1 + m(d)))}
    val userCounts = (0 to Days.daysBetween(day0, currentDate).getDays()) map {i => dayCounts(day0.plusDays(i))}
    JsObject(List(
        "day0" -> day0.toJson,
        "userCounts" -> JsArray(userCounts map {i => JsNumber(i)})
    ))
  }

  def index = AdminHtmlAction { implicit request =>
    Ok(views.html.adminDashboard())
  }

  def usersByDate = AdminJsonAction { implicit request =>
    Ok(userCountByDate)
  }

}
