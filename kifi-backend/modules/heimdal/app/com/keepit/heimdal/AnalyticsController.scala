package com.keepit.heimdal.controllers

import com.keepit.common.controller.HeimdalServiceController
import com.keepit.heimdal.{MetricManager, NoContextRestriction, GroupedCountMetricDefinition, UserEventType}

import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global //Might want to change this to a custom play one

import play.api.mvc.Action

import com.google.inject.Inject


class AnalyticsController @Inject() (metricManager: MetricManager) extends HeimdalServiceController {


  def adhocMetric(from : String, to: String, events: String, groupBy: String) = Action{ request =>
    val fromTime = DateTime.parse(from)
    val toTime = DateTime.parse(to)
    val eventsToConsider = events.split(",").map(UserEventType(_)).toSet
    val definition = new GroupedCountMetricDefinition(eventsToConsider, NoContextRestriction, groupBy)
    Async( metricManager.computeAdHocMteric(fromTime, toTime, definition).map(Ok(_))   ) 
  }


}
