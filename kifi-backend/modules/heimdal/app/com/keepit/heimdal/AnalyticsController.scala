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
  EventGrouping
}
import com.keepit.common.time._

import org.joda.time.DateTime

import play.api.libs.concurrent.Execution.Implicits.defaultContext //Might want to change this to a custom play one

import play.api.mvc.Action
import play.api.libs.json.{Json, JsObject, JsArray}

import com.google.inject.Inject

import views.html


class AnalyticsController @Inject() (metricManager: MetricManager) extends HeimdalServiceController {

  val definedRestrictions = Map[String, ContextRestriction](
    "none" -> NoContextRestriction,
    "noadmins" -> AnyContextRestriction("context.experiment", NotEqualTo(ContextStringData("admin")))
  )


  def adhocMetric(from : String, to: String, events: String, groupBy: String, breakDown: String, mode: String, filter: String, as: String) = Action{ request =>
    val doBreakDown = if (breakDown!="false" && groupBy!="_") true else false
    val fromTime = DateTime.parse(from)
    val toTime = if (to=="now") currentDateTime else DateTime.parse(to)
    val eventsToConsider = if (events=="all") AllEvents else SpecificEventSet(events.split(",").map(UserEventType(_)).toSet)

    val contextRestriction  = definedRestrictions(filter)

    val jsonFuture = if (mode=="users") {
      val definition = new GroupedUserCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(groupBy), doBreakDown)
      metricManager.computeAdHocMteric(fromTime, toTime, definition)
    } else {
      val definition = new GroupedEventCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(groupBy), doBreakDown)
      metricManager.computeAdHocMteric(fromTime, toTime, definition)
    }

    if (as=="json"){
      Async(jsonFuture.map{ json => Ok(json)})
    } else if (as=="pie") {
      Async(jsonFuture.map{ json =>
        var title = (if (mode=="users") "'Distinct User Count " else "'Event Count ") + s"for Events:$events from $from to $to'" 
        Ok(html.adhocPieChart(Json.stringify(json), title))
      })
    } else {
      Ok("'as' paramter must be either 'pie' or 'json'.")
    }
  }

  def rawEvents(events: String, limit: Int) = Action { request =>
    val eventsToConsider = if (events=="all") AllEvents else SpecificEventSet(events.split(",").map(UserEventType(_)).toSet)
    Async(metricManager.getLatestRawEvents(eventsToConsider, limit).map(Ok(_)))
  }


}
