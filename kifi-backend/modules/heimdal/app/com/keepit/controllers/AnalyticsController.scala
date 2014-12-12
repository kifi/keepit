package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.heimdal.{ SpecificEventSet, _ }
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsNumber, JsObject, Json }
import play.api.mvc.Action
import views.html

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class AnalyticsController @Inject() (
  userEventLoggingRepo: UserEventLoggingRepo,
  systemEventLoggingRepo: SystemEventLoggingRepo,
  anonymousEventLoggingRepo: AnonymousEventLoggingRepo,
  visitorEventLoggingRepo: VisitorEventLoggingRepo,
  nonUserEventLoggingRepo: NonUserEventLoggingRepo,
  metricManager: MetricManager)
    extends HeimdalServiceController {

  private def getRepo(repoEventTypeCode: String) = EventRepo.findByEventTypeCode(userEventLoggingRepo, systemEventLoggingRepo, anonymousEventLoggingRepo, nonUserEventLoggingRepo, visitorEventLoggingRepo)(repoEventTypeCode).get

  val adhocHelp = """
    | Returns simple event statistics
    | Usage: http://[184.169.206.118|b08]:9000/internal/heimdal/adhocMetric
    | Options (all optional):
    |   help          Print this message (ignoring all other parameters).
    |   from=$        Where $ is a valid ISO datetime string. Default: one hour ago.
    |   to=$          Where $ is a valid ISO datetime string or "now". Default: "now".
    |   events=$      Where $ is a comma seperated list of the event types to include or "all". Default: "all".
    |   groupBy=$     Where $ is a event field name that the results will be grouped by. Default: no grouping .
    |   breakDown=$   Where $ is 'true' or 'false'.
    |                 Setting this to 'true' means that if grouping by a field that can have multiple values the events will be broken down into multiple events with one value each.
    |                 Note that this caused events with the field missing to be ignored (will otherwise show up under 'null').
    |                 Default: "false".
    |   mode=$        Where $ is either "count" or "users". The former counts number of events, the latter counts (and returns, in json mode) distinct users. Default: "count".
    |   filters=$      Where $ specifies filter names to exclude certain events. Default: "none".
    |   as=$          Where $ specifies the output format. Currently supported: "pie" (renders a donut chart), "json". Default: "pie".
  """.stripMargin

  val rawHelp = """
    | Returns most recent events
    | Usage: http://[184.169.206.118|b08]:9000/internal/heimdal/rawEvents
    | Options (all optional):
    |   help          Print this message (ignoring all other parameters).
    |   events=$      Where $ is a comma seperated list of the event types to include or "all". Default: "all".
    |   limit=$       Where $ is the number of events to return. Default: 10.
  """.stripMargin

  val createHelp = """
    | Creates simple sliding time window metric. Accessed at http://[184.169.206.118|b08]:9000/internal/heimdal/getMetric
    | Usage: http://[184.169.206.118|b08]:9000/internal/heimdal/createMetric
    | Options (all optional):
    |   help          Print this message (ignoring all other parameters).
    |   name=$        Unique identifier for this metric. Default: "default"
    |   start=$       Where $ is a valid ISO datetime string. Default: one hour ago.
    |   window=$      Where $ is an integer specifing the sliding window size in hours. Default: 24.
    |   step=$        Where $ is an integer specifing the sliding window step in hours (i.e. how often do we compute). Default: 24.
    |   description=$ Where $ is a string describing the metric. Shows up in the UI.
    |   events=$      Where $ is a comma seperated list of the event types to include or "all". Default: "all".
    |   groupBy=$     Where $ is a event field name that the results will be grouped by. Default: no grouping .
    |   breakDown=$   Where $ is 'true' or 'false'.
    |                 Setting this to 'true' means that if grouping by a field that can have multiple values the events will be broken down into multiple events with one value each.
    |                 Note that this caused events with the field missing to be ignored (will otherwise show up under 'null').
    |                 Default: "false".
    |   mode=$        Where $ is either "count" or "users" or "count_unique". "count" counts number of events, "users" counts (and returns, in json mode) distinct users, "count_unique" counts unique values of the field specified in the "uniqueField" param. Default: "count".
    |   filters=$      Where $ specifies filter names to exclude certain events. Default: "none".
    |   uniqueField=$ Where $ specifies which fields unique values to count in "count_unique" mode.
  """.stripMargin

  def createMetric(repo: String, name: String, start: String, window: Int, step: Int, description: String, events: String, groupBy: String, breakDown: String, mode: String, filters: String, uniqueField: String) = Action { request =>
    require(repo != SystemEvent.typeCode, s"Metrics are not supported yet for system events")
    if (request.queryString.get("help").nonEmpty) Ok(createHelp)
    else {
      assert(window > 0)
      val startDT = DateTime.parse(start)
      metricManager.createMetric(MetricDescriptor(name, startDT, window, step, description, if (events == "all") Seq() else events.split(","), groupBy, breakDown.toBoolean, mode, filters.split(","), startDT, uniqueField))
      Ok("New metric created")
    }
  }

  def updateMetrics() = Action { request =>
    metricManager.updateAllMetrics()
    Ok("Update has been triggered. Please be patient. Calling this repeatedly in rapid succession will only make it take longer. If you think it didn't work, wait at least 30 seconds.")
  }

  def getAvailableMetrics(repo: String) = Action.async { request =>
    require(repo != SystemEvent.typeCode, s"Metrics are not supported yet for system events")
    metricManager.getAvailableMetrics.map { metricDescriptors =>
      Ok(Json.toJson(metricDescriptors))
    }
  }

  def getMetric(repo: String, name: String, as: String) = Action { request => //this is going to need some serious refactoring at some point
    require(repo != SystemEvent.typeCode, s"Metrics are not supported yet for system events")
    val names: Seq[String] = if (name == "all") {
      Await.result(metricManager.getAvailableMetrics.map { metricDescriptors => metricDescriptors.map(_.name) }, 20 seconds)
    } else {
      name.split(",")
    }

    val infoOptions: Seq[(Option[MetricDescriptor], String)] = Await.result(
      Future.sequence(names.map(metricManager.getMetricInfo(_))),
      20 seconds
    ).zip(names)

    var json = Json.obj()
    var errors = ""

    infoOptions.foreach {
      case (Some(desc), name) => {
        val data = Await.result(metricManager.getMetric(name), 20 second)
        if (data.isEmpty) {
          errors = errors + "No Data for Metric: " + name + "\n"
        } else {
          json = json.deepMerge {
            Json.obj(s"[$name] ${desc.description}" -> Json.toJson(data))
          }
        }
      }
      case (None, name) => {
        errors = errors + "Unknown Metric Name: " + name + "\n"
      }
    }

    if (as == "json") Ok(json)
    else Ok(html.storedMetricChart(json.toString, errors))
  }

  def getMetricData(repo: String, name: String) = Action.async { request =>
    require(repo != SystemEvent.typeCode, s"Metrics are not supported yet for system events")
    metricManager.getMetricInfo(name).flatMap { infoOption =>
      infoOption.map { desc =>
        metricManager.getMetric(name).map { data =>
          if (data.isEmpty) {
            Ok(Json.obj(
              "header" -> ("No Data for Metric: " + name),
              "data" -> Json.arr()
            ))
          } else {
            Ok(Json.obj(
              "header" -> s"[$name] ${desc.description}",
              "data" -> Json.toJson(data)
            ))
          }
        }
      } getOrElse {
        Promise.successful(Ok(Json.obj(
          "header" -> ("Unknown Metric: " + name),
          "data" -> Json.arr()
        ))).future
      }
    }
  }

  def adhocMetric(repo: String, from: String, to: String, events: String, groupBy: String, breakDown: String, mode: String, filters: String, as: String) = Action.async { request =>
    require(repo != SystemEvent.typeCode, s"Metrics are not supported yet for system events")
    if (request.queryString.get("help").nonEmpty) resolve(Ok(adhocHelp))
    else {
      val doBreakDown = if (breakDown != "false" && groupBy.startsWith("context")) true else false
      val fromTime = if (from == "") currentDateTime.minusHours(1) else DateTime.parse(from)
      val toTime = if (to == "now") currentDateTime else DateTime.parse(to)
      val eventsToConsider = if (events == "all") AllEvents else SpecificEventSet(events.split(",").map(EventType(_)).toSet)
      val contextRestriction = AndContextRestriction(filters.split(",").map(metricManager.definedRestrictions): _*)

      val jsonFuture = if (mode == "users") {
        val definition = new GroupedUserCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(groupBy), doBreakDown, as == "hist")
        metricManager.computeAdHocMteric(fromTime, toTime, definition)
      } else {
        val definition = new GroupedEventCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(groupBy), doBreakDown, as == "hist")
        metricManager.computeAdHocMteric(fromTime, toTime, definition)
      }

      if (as == "json") {
        jsonFuture.map { json => Ok(json) }
      } else if (as == "pie") {
        jsonFuture.map { json =>
          var title = (if (mode == "users") "'Distinct User Count " else "'Event Count ") + s"for Events:$events from $from to $to'"
          Ok(html.adhocPieChart(Json.stringify(json), title))
        }
      } else if (as == "hist") {
        jsonFuture.map { json =>
          var title = (if (mode == "users") "'Distinct User Count " else "'Event Count ") + s"for Events:$events from $from to $to'"
          Ok(html.adhocHistChart(Json.stringify(json), title))
        }
      } else {
        resolve(BadRequest("'as' paramter must be either 'pie' or 'json'."))
      }
    }
  }

  def rawEvents(repo: String, events: String, limit: Int, window: Int) = Action.async { request =>
    if (request.queryString.get("help").nonEmpty) resolve(Ok(rawHelp))
    else {
      val eventsToConsider = if (events == "all") AllEvents else SpecificEventSet(events.split(",").map(EventType(_)).toSet)
      getRepo(repo).getLatestRawEvents(eventsToConsider, limit, window).map(Ok(_))
    }
  }

  def getEventDescriptors(repo: String) = Action.async { request =>
    getRepo(repo).descriptors.getAll().map(descriptors => Ok(Json.toJson(descriptors)))
  }

  def updateEventDescriptors(repo: String) = Action.async { request =>
    val updatedDescriptors = Json.fromJson[Seq[EventDescriptor]](request.body.asJson.get).get
    val descriptors = getRepo(repo).descriptors
    Future.sequence(updatedDescriptors.map(descriptors.upsert)).map(counts => Ok(JsNumber(counts.sum)))
  }

  def deleteUser(userId: Id[User]) = Action.async { request =>
    SafeFuture {
      userEventLoggingRepo.delete(userId)
      Ok
    }
  }

  def incrementUserProperties(userId: Id[User]) = Action.async { request =>
    val increments = request.body.asJson.get.as[JsObject].value.mapValues(_.as[Double]).toMap
    SafeFuture {
      userEventLoggingRepo.incrementUserProperties(userId, increments)
      Ok
    }
  }

  def setUserProperties(userId: Id[User]) = Action.async { request =>
    val properties = Json.fromJson[HeimdalContext](request.body.asJson.get).get
    SafeFuture {
      userEventLoggingRepo.setUserProperties(userId, properties)
      Ok
    }
  }

  def setUserAlias(userId: Id[User], externalId: ExternalId[User]) = Action.async { request =>
    SafeFuture {
      userEventLoggingRepo.setUserAlias(userId, externalId)
      Ok
    }
  }
}
