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
  MetricDescriptor
}
import com.keepit.common.time._

import org.joda.time.DateTime

import play.api.libs.concurrent.Execution.Implicits.defaultContext //Might want to change this to a custom play one

import play.api.mvc.{Action}
import play.api.libs.json.{Json, JsObject, JsArray}

import com.google.inject.Inject

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

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
    |   mode=$        Where $ is either "count" or "users". The former counts number of events, the latter counts (and returns, in json mode) distinct users. Default: "count".
    |   fitler=$      Where $ specifies a filter name to exclude certain events. Currently supported: "none", "noadmins". Default: "none".
  """.stripMargin

  def createMetric(name: String, start: String, window: Int, step: Int, description: String, events: String, groupBy: String, breakDown: String, mode: String, filter: String) = Action { request =>
    if (request.queryString.get("help").nonEmpty) Ok(createHelp)
    else {
      assert(window>0)
      val startDT = DateTime.parse(start)
      metricManager.createMetric(MetricDescriptor(name, startDT, window, step, description, if (events=="all") Seq() else events.split(","), groupBy, breakDown.toBoolean, mode, filter, startDT, ""))
      Ok("New metric created")
    }
  }

  def updateMetrics() = Action { request =>
    metricManager.updateAllMetrics()
    Ok("Update has been triggered. Please be patient. Calling this repeatedly in rapid succession will only make it take longer. If you think it didn't work, wait at least 30 seconds.")
  }

  def getAvailableMetrics = Action { request =>
    Async(metricManager.getAvailableMetrics.map{ metricDescriptors =>
      Ok(Json.toJson(metricDescriptors))
    })
  }

  def getMetric(name: String, as: String) = Action { request => //this is going to need some serious refactoring at some point
    val names : Seq[String] = if (name=="all") {
      Await.result(metricManager.getAvailableMetrics.map{ metricDescriptors => metricDescriptors.map(_.name)}, 20 seconds)
    } else {
      name.split(",")
    }

    val infoOptions : Seq[(Option[MetricDescriptor], String)] = Await.result(
      Future.sequence(names.map(metricManager.getMetricInfo(_))),
      20 seconds
    ).zip(names)

    var json = Json.obj()
    var errors = ""

    infoOptions.foreach{
      case (Some(desc), name) => {
        val data = Await.result(metricManager.getMetric(name), 5 second)
        if (data.isEmpty){
          errors = errors + "No Data for Metric: " + name + "\n"
        } else {
          json = json.deepMerge{
            Json.obj(s"[$name] ${desc.description}" -> Json.toJson(data))
          }
        }
      }
      case (None, name) => {
        errors = errors + "Unknown Metric Name: " + name + "\n"
      }
    }

    if (as=="json") Ok(json)
    else Ok(html.storedMetricChart(json.toString, errors))

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

  //SEARCH EXPERIMENTS ENDPOINT SKELETON
  def createMetricForSeachExperiment(experiment: String, experimentStart: String) = Action { request =>
    val exStart = DateTime.parse(experimentStart)
    
    val searches_with_kifi_result = MetricDescriptor( //And yes, I know a bunch if the fields here need type safeing
      name        = "se_" + experiment + "_unique_searches_with_kifi_result", //identifier for the metric. This needs to be unique
      start       = exStart, //we start computing the metrics from this time point
      window      = 24, //every time the metric is computed we aggregate over the last 24 h
      step        = 24, //and we compute every 24 hours
      description = "Automated metric for search experiments. Not meant for immediate human consumption.",
      events      = Seq[String]("search_performed"), //which events are we looking at here
      groupBy     = "_", //Don't group by anything
      breakDown   = false, //Can only be true when there is a grouping
      mode        = "count_unique", //Count unique values in the uniqueField argument
      filter      = "withkifiresults",
      lastUpdate  = exStart, //this is a new metric
      uniqueField = "context.searchId" 
    )

    val total_searches = MetricDescriptor(
      name        = "se_" + experiment + "_total_unique_searches",  
      start       = exStart,
      window      = 24,
      step        = 24,
      description = "Automated metric for search experiments. Not meant for immediate human consumption.",
      events      = Seq[String]("search_performed"),
      groupBy     = "_",
      breakDown   = false,
      mode        = "count_unique", 
      filter      = "none",
      lastUpdate  = exStart,
      uniqueField = "context.searchId" 
    )

    val kifi_results_clicked = MetricDescriptor(
      name        = "se_" + experiment + "_unique_searches_with_kifi_result",  
      start       = exStart, 
      window      = 24,
      step        = 24,
      description = "Automated metric for search experiments. Not meant for immediate human consumption.",
      events      = Seq[String]("search_result_clicked"),
      groupBy     = "_",
      breakDown   = false,
      mode        = "count", 
      filter      = "kifiresultclicked",
      lastUpdate  = exStart,
      uniqueField = "" 
    )

    val total_results_clicked_with_kifi_result = MetricDescriptor(
      name        = "se_" + experiment + "_unique_searches_with_kifi_result",  
      start       = exStart, 
      window      = 24,
      step        = 24,
      description = "Automated metric for search experiments. Not meant for immediate human consumption.",
      events      = Seq[String]("search_result_clicked"),
      groupBy     = "_",
      breakDown   = false,
      mode        = "count", 
      filter      = "withkifiresults",
      lastUpdate  = exStart,
      uniqueField = "" 
    )

    metricManager.createMetric(searches_with_kifi_result)
    metricManager.createMetric(total_searches)
    metricManager.createMetric(kifi_results_clicked)
    metricManager.createMetric(total_results_clicked_with_kifi_result)

    Ok("")
  } 


}
