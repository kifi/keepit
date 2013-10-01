package com.keepit.heimdal.controllers

import com.keepit.common.controller.HeimdalServiceController
import com.keepit.heimdal.{MetricManager, NoContextRestriction, GroupedEventCountMetricDefinition, UserEventType, SpecificEventSet, AllEvents, GroupedUserCountMetricDefinition}
import com.keepit.common.time._

import org.joda.time.DateTime

import play.api.libs.concurrent.Execution.Implicits.defaultContext //Might want to change this to a custom play one

import play.api.mvc.Action
import play.api.libs.json.{Json, JsObject, JsArray}

import com.google.inject.Inject


class AnalyticsController @Inject() (metricManager: MetricManager) extends HeimdalServiceController {


  def adhocMetric(from : String, to: String, events: String, groupBy: String, breakDown: String, mode: String) = Action{ request =>
    //ALL EVENTS!
    val doBreakDown = if (breakDown!="false" && groupBy!="_") true else false
    val fromTime = DateTime.parse(from)
    val toTime = if (to=="now") currentDateTime else DateTime.parse(to)
    val eventsToConsider = if (events=="all") AllEvents else SpecificEventSet(events.split(",").map(UserEventType(_)).toSet)
    if (mode=="users") {
      val definition = new GroupedUserCountMetricDefinition(eventsToConsider, NoContextRestriction, groupBy, doBreakDown)
      Async( metricManager.computeAdHocMteric(fromTime, toTime, definition).map(Ok(_)) ) 
    } else {
      val definition = new GroupedEventCountMetricDefinition(eventsToConsider, NoContextRestriction, groupBy, doBreakDown)
      Async( metricManager.computeAdHocMteric(fromTime, toTime, definition).map(Ok(_)) ) 
    }
  }

  def rawEvents(events: String, limit: Int) = Action { request =>
    val eventsToConsider = if (events=="all") AllEvents else SpecificEventSet(events.split(",").map(UserEventType(_)).toSet)
    Async(metricManager.getLatestRawEvents(eventsToConsider, limit).map(Ok(_)))
  }


}
