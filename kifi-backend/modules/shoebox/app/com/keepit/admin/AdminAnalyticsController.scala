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

    val userMetrics = Seq[String](
      "alive_weekly",
      "active_weekly"
    )

    val keepMetrics = Seq[String](
      "keeps_weekly",
      "private_keeps_weekly",
      "public_keeps_weekly"
    )

    val messageMetrics = Seq[String](
      "messagers_weekly",
      "message_breakdown_weekly"
    )

  def index() = AdminHtmlAction { request =>
    heimdal.updateMetrics()
    val userMetricsFuture = Future.sequence(userMetrics.map{ metricName =>
      heimdal.getMetricData(metricName)
    })
    val keepMetricsFuture = Future.sequence(keepMetrics.map{ metricName =>
      heimdal.getMetricData(metricName)
    })
    val messageMetricsFuture = Future.sequence(messageMetrics.map{ metricName =>
      heimdal.getMetricData(metricName)
    })
    val dataFuture = Future.sequence(Seq(userMetricsFuture, keepMetricsFuture, messageMetricsFuture))
    Async(dataFuture.map{ data =>
      val jsonData = data.map{ sectionData =>
        Json.stringify(Json.toJson(sectionData))
      } 
      Ok(html.admin.analyticsDashboardView(    
        jsonData(0), jsonData(1), jsonData(2)
      ))
    })
  }

}
