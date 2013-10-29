package com.keepit.controllers.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.heimdal.HeimdalServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{Json, JsObject, JsArray}

import views.html

import scala.concurrent.{Future, Promise}
import scala.util.{Success, Failure}

import com.google.inject.Inject


case class MetricAuxInfo(helpText: String, legend: Map[String,String], shift: Map[String, Int] = Map[String, Int](), totalable : Boolean = true, resolution: Int = 1)

case class MetricWithAuxInfo(data: JsObject, auxInfo: MetricAuxInfo)

class AdminAnalyticsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    heimdal: HeimdalServiceClient
  )
  extends AdminController(actionAuthenticator) {

  val installMetrics = Map[String, MetricAuxInfo](
    "invites_sent_daily" -> MetricAuxInfo("nothing yet", Map("null" -> "Invites Sent"), Map("Invites Sent" -> 462)),
    "new_installs_daily" -> MetricAuxInfo("nothing yet", Map("null" -> "Users Installed"), Map("Users Installed" -> 362))
  )

  val userMetrics = Map[String, MetricAuxInfo](
    "alive_weekly" -> MetricAuxInfo("nothing yet", Map("null" -> "Connected Users")),
    "active_weekly" -> MetricAuxInfo("nothing yet", Map("null" -> "Active Users"))
  )

  val keepActivityMetrics = Map[String, MetricAuxInfo](
    "keeps_daily" -> MetricAuxInfo("nothing yet", Map(
      "0" -> "public",
      "1" -> "private"
    ))
  )

  val keepMetrics = Map[String, MetricAuxInfo](
    "keeps_weekly" -> MetricAuxInfo("nothing yet", Map(
      "0" -> "public",
      "1" -> "private"
    ), Map(
      "public" -> 31450,
      "private" -> 22407,
      "total" -> 53857
    ), resolution=7),
    "private_keeps_weekly" -> MetricAuxInfo("nothing yet", Map(), Map(
      "INIT_LOAD" -> 20461,
      "HOVER_KEEP" -> 1804,
      "SITE" -> 33,
      "total" -> 22407
    ), resolution=7),
    "public_keeps_weekly" -> MetricAuxInfo("nothing yet", Map(), Map(
      "HOVER_KEEP" -> 23967,
      "SITE" -> 467,
      "total" -> 31450
    ), resolution=7)
  )

  val messageMetrics = Map[String, MetricAuxInfo](
    "messagers_daily" -> MetricAuxInfo("nothing yet", Map("null" -> "Users"), totalable=false),
    "messagers_weekly" -> MetricAuxInfo("nothing yet", Map(), totalable=false),
    "message_breakdown_weekly" -> MetricAuxInfo("nothing yet", Map())
  )

  val searchMetrics = Map[String, MetricAuxInfo](
    "kifi_result_clickers_daily" -> MetricAuxInfo("nothing yet", Map("null" -> "Users")),
    "results_clicked_daily" -> MetricAuxInfo("nothing yet", Map(
      "kifi_result_clicked" -> "Kifi Clicks",
      "search_result_clicked" -> "Other Clicks"
    )),
    "total_searches_daily" -> MetricAuxInfo("nothing yet", Map(
      "null" -> "Total Searches"
    ), totalable=false)
  )

  val metrics = Map[String, Map[String, MetricAuxInfo]](
    "installMetrics" -> installMetrics,
    "userMetrics" -> userMetrics,
    "keepActivityMetrics" -> keepActivityMetrics,
    "keepMetrics" -> keepMetrics,
    "messageMetrics" -> messageMetrics,
    "searchMetrics" -> searchMetrics
  )

  private def augmentMetricData(metricData: JsObject, auxInfo: MetricAuxInfo): JsObject = {
    metricData.deepMerge{Json.obj(
        "help" -> auxInfo.helpText,
        "legend" -> JsObject(auxInfo.legend.mapValues(Json.toJson(_)).toSeq),
        "shift" -> JsObject(auxInfo.shift.mapValues(Json.toJson(_)).toSeq),
        "totalable" -> auxInfo.totalable,
        "resolution" -> auxInfo.resolution
    )}
  }

  private def metricData : Future[Map[String, JsArray]] = {
    val innerFutures = metrics.mapValues{ groupMap => 
      Future.sequence(groupMap.toSeq.map{ case (metricName, auxInfo) =>
        heimdal.getMetricData(metricName).map{augmentMetricData(_, auxInfo)}
      })
    }
    val keys: Seq[String] = innerFutures.keys.toSeq
    val valuesFuture: Future[Seq[JsArray]] = Future.sequence(innerFutures.values.toSeq).map{ values =>
      values.map{ sectionData =>
        Json.toJson(sectionData).as[JsArray]
      }
    }
    val promise = Promise[Map[String, JsArray]]();
    valuesFuture.onComplete{
      case Success(v) => promise.success(keys.zip(v).toMap)
      case Failure(e) => promise.failure(e)
    }
    promise.future
  }

  def index() = AdminHtmlAction { request =>
    heimdal.updateMetrics()
    Async(metricData.map{ dataMap =>
      Ok(html.admin.analyticsDashboardView(dataMap.mapValues(Json.stringify(_))))
    })
  }

}
