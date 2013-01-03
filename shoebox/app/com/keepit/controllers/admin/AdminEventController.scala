package com.keepit.controllers.admin

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.common.db.ExternalId
import com.keepit.common.async._
import com.keepit.model._
import com.keepit.inject._
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
import java.sql.Connection
import com.keepit.common.logging.Logging
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.search.graph._
import com.keepit.search._
import com.keepit.common.social.UserWithSocial
import org.apache.commons.lang3.StringEscapeUtils
import com.keepit.search.ArticleSearchResultStore
import com.keepit.common.controller.FortyTwoController
import play.api.libs.json._
import com.keepit.common.analytics._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.analytics.reports._
import com.keepit.common.time._
import org.joda.time._
import play.api.data._
import play.api.data.Forms._


object AdminEventController extends FortyTwoController {

  def buildReport() = AdminHtmlAction { request =>

    implicit val playrequest = request.request
    val reportForm = Form(
        "reportName" -> text
    )

    val reportName = reportForm.bindFromRequest.get.toLowerCase match {
      case "daily" => Reports.DailyReports
      case "admin" => Reports.DailyAdminReports
      case unknown => throw new Exception("Unknown report: %s".format(unknown))
    }

    val rb = inject[ReportBuilderPlugin]

    rb.buildReports(rb.defaultStartTime, rb.defaultEndTime, reportName)

    Redirect(com.keepit.controllers.admin.routes.AdminEventController.reportList())
  }

  def getReport(reportName: String) = AdminHtmlAction { request =>

    log.info(reportName)

    val report = inject[ReportStore].get(reportName).get

    Ok(report.toCSV).withHeaders(("Content-Disposition", "attachment; filename=" + reportName + ".csv"))
  }

  def reportList() = AdminHtmlAction { request =>

    val availableReports = inject[ReportStore].getReports() // strip ".json"

    Ok(views.html.reports(availableReports))
  }
}
