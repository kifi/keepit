package com.keepit.controllers.admin

import play.api.Play.current
import play.api.mvc._

import com.keepit.inject._
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
import com.keepit.common.controller.FortyTwoController
import play.api.libs.json._
import com.keepit.common.analytics._
import com.keepit.common.analytics.reports._
import play.api.data._
import play.api.data.Forms._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._


object AdminEventController extends FortyTwoController {

  def buildReport() = AdminHtmlAction { request =>

    implicit val playrequest = request.request
    val reportForm = Form(
        "reportName" -> text
    )

    val reportGroup = reportForm.bindFromRequest.get.toLowerCase match {
      case "daily" => Reports.DailyReports
      case "admin" => Reports.DailyAdminReports
      case "experiment" =>
        val activeExperiments = inject[SearchConfigManager].activeExperiments
        Reports.searchExperimentReports(activeExperiments)
      case unknown => throw new Exception("Unknown report: %s".format(unknown))
    }

    val rb = inject[ReportBuilderPlugin]

    rb.buildReports(rb.defaultStartTime, rb.defaultEndTime, reportGroup)


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

  def realTimeViewer() = AdminHtmlAction { implicit request =>
    Ok(views.html.adminRTEventViewer())
  }

  def eventStream() = WebSocket.async[JsValue] { implicit request  =>

    inject[ActivityStream].newStream()
  }
}
