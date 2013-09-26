package com.keepit.heimdal.controllers

import com.keepit.common.controller.HeimdalServiceController
import com.keepit.heimdal.{MetricManager, NoContextRestriction, GroupedEventCountMetricDefinition, UserEventType, SpecificEventSet, AllEvents}
import com.keepit.common.time._

import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global //Might want to change this to a custom play one

import play.api.mvc.Action

import com.google.inject.Inject


class AnalyticsController @Inject() (metricManager: MetricManager) extends HeimdalServiceController {


  def adhocMetric(from : String, to: String, events: String, groupBy: String, breakDown: String) = Action{ request =>
    //ALL EVENTS!
    val doBreakDown = if (breakDown!="false" && groupBy!="_") true else false
    val fromTime = DateTime.parse(from)
    val toTime = if (to=="now") currentDateTime else DateTime.parse(to)
    val eventsToConsider = if (events=="all") AllEvents else SpecificEventSet(events.split(",").map(UserEventType(_)).toSet)
    val definition = new GroupedEventCountMetricDefinition(eventsToConsider, NoContextRestriction, groupBy, doBreakDown)
    Async( metricManager.computeAdHocMteric(fromTime, toTime, definition).map(Ok(_))   ) 
  }


}
