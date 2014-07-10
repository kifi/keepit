package com.keepit.controllers.admin

import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.heimdal._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import views.html

import scala.concurrent.{ Future, Promise }
import com.google.inject.Inject
import com.keepit.common.db.Id
import scala.collection.mutable.{ ListBuffer, Map => MutableMap }
import play.api.libs.json.JsArray
import scala.util.Failure
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsNumber
import scala.util.Success
import play.api.libs.json.JsObject

case class MetricAuxInfo(helpText: String, legend: Map[String, String], shift: Map[String, Int] = Map[String, Int](), totalable: Boolean = true, resolution: Int = 1)
object MetricAuxInfo {
  def augmentMetricData(metricData: JsObject, auxInfo: MetricAuxInfo): JsObject = {
    metricData.deepMerge {
      Json.obj(
        "help" -> auxInfo.helpText,
        "legend" -> JsObject(auxInfo.legend.mapValues(Json.toJson(_)).toSeq),
        "shift" -> JsObject(auxInfo.shift.mapValues(Json.toJson(_)).toSeq),
        "totalable" -> auxInfo.totalable,
        "resolution" -> auxInfo.resolution
      )
    }
  }

  def ungroupMetricById[T](ids: Seq[Option[Id[T]]], metricGroupedById: JsObject): Map[Option[Id[T]], JsObject] = {
    val dataPointsById = MutableMap[Option[Id[T]], ListBuffer[JsValue]]()
    ids.foreach(dataPointsById.update(_, new ListBuffer[JsValue]()))

    val header = (metricGroupedById \ "header").as[String]
    val dataByTime = (metricGroupedById \ "data").as[JsArray]
    dataByTime.value.foreach { dataWithTime =>
      val time = (dataWithTime \ "time")
      val counts = (dataWithTime \ "data").as[JsArray].value.map { countWithId =>
        val id = (countWithId \ "_id") match {
          case JsArray(Seq(JsNumber(id))) => Some(Id[T](id.toLong))
          case JsNull => None
        }
        val count = (countWithId \ "count")
        id -> count
      }.toMap

      ids.foreach { id =>
        val idData = counts.get(id).map(c => Json.arr(Json.obj("count" -> c))).getOrElse(Json.arr())
        dataPointsById(id).append(Json.obj("time" -> time, "data" -> idData))
      }
    }
    dataPointsById.map { case (id, points) => id -> wrapIntoMetric(id, header, points) }.toMap
  }

  private def wrapIntoMetric[T](id: Option[Id[T]], header: String, points: Seq[JsValue]): JsObject =
    Json.obj(
      "header" -> JsString(header + s": ${id.map(_.toString).getOrElse("None")}"),
      "data" -> JsArray(points)
    )
}

case class MetricWithAuxInfo(data: JsObject, auxInfo: MetricAuxInfo)

class AdminAnalyticsController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  heimdal: HeimdalServiceClient)
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
      "false" -> "public",
      "true" -> "private"
    ))
  )

  val keepMetrics = Map[String, MetricAuxInfo](
    "keeps_weekly" -> MetricAuxInfo("nothing yet", Map(
      "false" -> "public",
      "true" -> "private"
    ), Map(
      "public" -> 31450,
      "private" -> 22407,
      "total" -> 53857
    ), resolution = 7),
    "private_keeps_weekly" -> MetricAuxInfo("nothing yet", Map(), Map(
      "INIT_LOAD" -> 20461,
      "HOVER_KEEP" -> 1804,
      "SITE" -> 33,
      "total" -> 22407
    ), resolution = 7),
    "public_keeps_weekly" -> MetricAuxInfo("nothing yet", Map(), Map(
      "HOVER_KEEP" -> 23967,
      "SITE" -> 467,
      "total" -> 31450
    ), resolution = 7)
  )

  val messageMetrics = Map[String, MetricAuxInfo](
    "messagers_daily" -> MetricAuxInfo("nothing yet", Map("null" -> "Users"), totalable = false),
    "messagers_weekly" -> MetricAuxInfo("nothing yet", Map(), totalable = false),
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
    ), totalable = false)
  )

  val keeperFraction = Map[String, MetricAuxInfo](
    "alive_weekly" -> MetricAuxInfo("nothing yet", Map("null" -> "Connected Users")),
    "keepers_weekly_nogroup" -> MetricAuxInfo("nothing yet", Map("null" -> "Keeping Users"))
  )

  val messagerFraction = Map[String, MetricAuxInfo](
    "alive_weekly" -> MetricAuxInfo("nothing yet", Map("null" -> "Connected Users")),
    "messagers_weekly_nogroup" -> MetricAuxInfo("nothing yet", Map("null" -> "Messaging Users"))
  )

  val metrics = Map[String, Map[String, MetricAuxInfo]](
    "installMetrics" -> installMetrics,
    "userMetrics" -> userMetrics,
    "keepActivityMetrics" -> keepActivityMetrics,
    "keepMetrics" -> keepMetrics,
    "messageMetrics" -> messageMetrics,
    "searchMetrics" -> searchMetrics,
    "keeperFraction" -> keeperFraction,
    "messagerFraction" -> messagerFraction
  )

  private def userMetricData: Future[Map[String, JsArray]] = {
    val innerFutures = metrics.mapValues { groupMap =>
      Future.sequence(groupMap.toSeq.map {
        case (metricName, auxInfo) =>
          heimdal.getMetricData[UserEvent](metricName).map { MetricAuxInfo.augmentMetricData(_, auxInfo) }
      })
    }
    val keys: Seq[String] = innerFutures.keys.toSeq
    val valuesFuture: Future[Seq[JsArray]] = Future.sequence(innerFutures.values.toSeq).map { values =>
      values.map { sectionData =>
        Json.toJson(sectionData).as[JsArray]
      }
    }
    val promise = Promise[Map[String, JsArray]]();
    valuesFuture.onComplete {
      case Success(v) => promise.success(keys.zip(v).toMap)
      case Failure(e) => promise.failure(e)
    }
    promise.future
  }

  def index() = AdminHtmlAction.authenticatedAsync { request =>
    heimdal.updateMetrics()
    userMetricData.map { dataMap =>
      Ok(html.admin.analyticsDashboardView(dataMap.mapValues(Json.stringify(_))))
    }
  }

  def getEventDescriptors() = AdminHtmlAction.authenticatedAsync { request =>
    Future.sequence(HeimdalEventCompanion.all.toSeq.map { companion =>
      heimdal.getEventDescriptors(companion).map { descriptors =>
        companion.typeCode -> descriptors
      }
    }).map { descriptorsByRepo => Ok(html.admin.eventDescriptors(descriptorsByRepo: _*)) }
  }

  def updateEventDescriptors() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    val descriptorsWithCode = body.keys.collect {
      case key if key.endsWith(":description") =>
        val Seq(code, name, _): Seq[String] = key.split(":")
        val description = Some(body(s"$code:$name:description")).filter(_.nonEmpty)
        val mixpanel = body.contains(s"$code:$name:mixpanel")
        val eventName = EventType(name)
        code -> EventDescriptor(eventName, description, mixpanel)
    }

    Future.sequence(
      descriptorsWithCode.groupBy(_._1).mapValues(_.map(_._2)).map {
        case (code, descriptors) => heimdal.updateEventDescriptors(descriptors.toSeq)(HeimdalEventCompanion.byTypeCode(code))
      }
    ).map(_ => Redirect(routes.AdminAnalyticsController.getEventDescriptors()))
  }

  def getEvents(repo: String, events: Option[String], limit: Int, window: Int) = AdminHtmlAction.authenticatedAsync { request =>
    val eventNames = events.map(_.split(",")).getOrElse(Array.empty).map(EventType.apply)
    heimdal.getRawEvents(window, limit, eventNames: _*)(HeimdalEventCompanion.byTypeCode(repo)).map(Ok(_))
  }
}
