package com.keepit.controllers.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.heimdal.HeimdalServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{Json, JsObject}

import views.html

import scala.concurrent.Future

import com.google.inject.Inject


case class MetricAuxInfo(helpText: String, legend: Map[String,String], shift: Map[String, Int] = Map[String, Int]())

class AdminAnalyticsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    heimdal: HeimdalServiceClient
  )
  extends AdminController(actionAuthenticator) {

  val installMetrics = Map[String, MetricAuxInfo](
    "invites_sent_daily" -> MetricAuxInfo("nothing yet", Map("null" -> "Number Sent"), Map("Number Sent" -> 462)),
    "new_installs_daily" -> MetricAuxInfo("nothing yet", Map("null" -> "Users"), Map("Users" -> 402))
  )

  val userMetrics = Map[String, MetricAuxInfo](
    "alive_weekly" -> MetricAuxInfo("nothing yet", Map("null" -> "Users")),
    "active_weekly" -> MetricAuxInfo("nothing yet", Map("null" -> "Users"))
  )

  val keepMetrics = Map[String, MetricAuxInfo](
    "keeps_weekly" -> MetricAuxInfo("nothing yet", Map(
      "0" -> "public",
      "1" -> "private"
    ), Map(
      "public" -> 31450,
      "private" -> 22407,
      "total" -> 53857
    )),
    "private_keeps_weekly" -> MetricAuxInfo("nothing yet", Map(), Map(
      "INIT_LOAD" -> 20461,
      "HOVER_KEEP" -> 1804,
      "SITE" -> 33,
      "total" -> 22407
    )),
    "public_keeps_weekly" -> MetricAuxInfo("nothing yet", Map(), Map(
      "HOVER_KEEP" -> 23967,
      "SITE" -> 467,
      "total" -> 31450
    ))
  )

  val messageMetrics = Map[String, MetricAuxInfo](
    "messagers_weekly" -> MetricAuxInfo("nothing yet", Map()),
    "message_breakdown_weekly" -> MetricAuxInfo("nothing yet", Map())
  )

  private def augmentMetricData(metricData: JsObject, auxInfo: MetricAuxInfo): JsObject = {
    metricData.deepMerge{Json.obj(
        "help" -> auxInfo.helpText,
        "legend" -> JsObject(auxInfo.legend.mapValues(Json.toJson(_)).toSeq),
        "shift" -> JsObject(auxInfo.shift.mapValues(Json.toJson(_)).toSeq)
    )}
  }

  def index() = AdminHtmlAction { request =>
    heimdal.updateMetrics()
    val installMetricsFuture = Future.sequence(installMetrics.toSeq.map{ case (metricName, auxInfo) =>
      heimdal.getMetricData(metricName).map{augmentMetricData(_, auxInfo)}
    })
    val userMetricsFuture = Future.sequence(userMetrics.toSeq.map{ case (metricName, auxInfo) =>
      heimdal.getMetricData(metricName).map{augmentMetricData(_, auxInfo)}
    })
    val keepMetricsFuture = Future.sequence(keepMetrics.toSeq.map{ case (metricName, auxInfo) =>
      heimdal.getMetricData(metricName).map{augmentMetricData(_, auxInfo)}
    })
    val messageMetricsFuture = Future.sequence(messageMetrics.toSeq.map{ case (metricName, auxInfo) =>
      heimdal.getMetricData(metricName).map{augmentMetricData(_, auxInfo)}
    })
    val dataFuture = Future.sequence(Seq(installMetricsFuture, userMetricsFuture, keepMetricsFuture, messageMetricsFuture))


    Async(dataFuture.map{ data =>
      val jsonData = data.map{ sectionData =>
        Json.stringify(Json.toJson(sectionData))
      } 
      Ok(html.admin.analyticsDashboardView(    
        jsonData(0), jsonData(1), jsonData(2), jsonData(3)
      ))
    })
  }

}
