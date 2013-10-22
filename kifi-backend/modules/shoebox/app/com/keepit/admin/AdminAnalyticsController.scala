package com.keepit.controllers.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.heimdal.HeimdalServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import views.html

import scala.concurrent.Future

import com.google.inject.Inject


class AdminAnalyticsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    heimdal: HeimdalServiceClient
  )
  extends AdminController(actionAuthenticator) {

    val mainMetricsToShow = Seq[String](
      "alive_weekly",
      "keepers_weekly",
      "public_private_keeps_weekly",
      "weekly_total_messagers",
      "weekly_total_message_starters"
    )

  def index() = AdminHtmlAction { request =>
    heimdal.updateMetrics()
    val dataFuture = Future.sequence(mainMetricsToShow.map{ metricName =>
      heimdal.getMetricData(metricName)
    })
    Async(dataFuture.map{ data =>
      Ok(html.admin.analyticsDashboardView(Json.stringify(Json.toJson(data))))
    })
  }

}
