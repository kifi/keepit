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

/**
 * Charts, etc.
 */
object AdminDashboardController extends FortyTwoController {

  def index = AdminHtmlAction { implicit request => 
    // TODO: data for charts
    Ok(views.html.adminDashboard())
  }

}
