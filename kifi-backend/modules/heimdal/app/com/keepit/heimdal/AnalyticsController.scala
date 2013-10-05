package com.keepit.heimdal.controllers

import com.keepit.common.controller.HeimdalServiceController
import com.keepit.heimdal.{
  MetricManager, 
  NoContextRestriction, 
  GroupedEventCountMetricDefinition, 
  UserEventType, 
  SpecificEventSet, 
  AllEvents, 
  GroupedUserCountMetricDefinition, 
  ContextRestriction, 
  AnyContextRestriction, 
  NotEqualTo,
  ContextStringData,
  EventGrouping,
  MetricDesriptor
}
import com.keepit.common.time._

import org.joda.time.DateTime

import play.api.libs.concurrent.Execution.Implicits.defaultContext //Might want to change this to a custom play one

import play.api.mvc.{Action}
import play.api.libs.json.{Json, JsObject, JsArray}

import com.google.inject.Inject

import views.html


class AnalyticsController @Inject() (metricManager: MetricManager) extends HeimdalServiceController {


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
    |   fitler=$      Where $ specifies a filter name to exclude certain events. Currently supported: "none", "noadmins". Default: "none".
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

  def createMetric(name: String, start: String, window: Int, step: Int, description: String, events: String, groupBy: String, breakDown: String, mode: String, filter: String) = Action { request =>
    assert(window>0)
    val startDT = DateTime.parse(start)
    metricManager.createMetric(MetricDesriptor(name, startDT, window, step, description, if (events=="all") Seq() else events.split(","), groupBy, breakDown.toBoolean, mode, filter, startDT))
    Ok("New metric created")
  }

  def updateMetrics() = Action { request =>
    metricManager.updateAllMetrics()
    Ok("Update has been triggered. Please be patient. Calling this repeatedly in rapid succession will only make it take longer. If you think it didn't work, wait at least 30 seconds.")
  }

  def adhocMetric(from : String, to: String, events: String, groupBy: String, breakDown: String, mode: String, filter: String, as: String) = Action{ request =>
    if (request.queryString.get("help").nonEmpty) Ok(adhocHelp)
    else {
      val doBreakDown = if (breakDown!="false" && groupBy.startsWith("context")) true else false
      val fromTime = if (from=="") currentDateTime.minusHours(1) else DateTime.parse(from)
      val toTime = if (to=="now") currentDateTime else DateTime.parse(to)
      val eventsToConsider = if (events=="all") AllEvents else SpecificEventSet(events.split(",").map(UserEventType(_)).toSet)

      val contextRestriction  = metricManager.definedRestrictions(filter)

      val jsonFuture = if (mode=="users") {
        val definition = new GroupedUserCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(groupBy), doBreakDown, as=="hist")
        metricManager.computeAdHocMteric(fromTime, toTime, definition)
      } else {
        val definition = new GroupedEventCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(groupBy), doBreakDown, as=="hist")
        metricManager.computeAdHocMteric(fromTime, toTime, definition)
      }

      if (as=="json"){
        Async(jsonFuture.map{ json => Ok(json)})
      } else if (as=="pie") {
        Async(jsonFuture.map{ json =>
          var title = (if (mode=="users") "'Distinct User Count " else "'Event Count ") + s"for Events:$events from $from to $to'" 
          Ok(html.adhocPieChart(Json.stringify(json), title))
        })
      } else if (as=="hist"){
        Async(jsonFuture.map{ json =>
          var title = (if (mode=="users") "'Distinct User Count " else "'Event Count ") + s"for Events:$events from $from to $to'" 
          Ok(html.adhocHistChart(Json.stringify(json), title))
        })
      } else {
        BadRequest("'as' paramter must be either 'pie' or 'json'.")
      }
    }
  }

  def rawEvents(events: String, limit: Int) = Action { request =>
    if (request.queryString.get("help").nonEmpty) Ok(rawHelp)
    else {
      val eventsToConsider = if (events=="all") AllEvents else SpecificEventSet(events.split(",").map(UserEventType(_)).toSet)
      Async(metricManager.getLatestRawEvents(eventsToConsider, limit).map(Ok(_)))
    }
  }


}
