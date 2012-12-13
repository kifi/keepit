package com.keepit.common.analytics.reports

import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.analytics._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.inject._
import play.api.Play.current
import org.joda.time._
import com.keepit.common.logging.Logging
import com.mongodb.casbah.Imports._
import scala.util.Random

object Parsers {
  def dateCountParser(r: DBObject) = {
    val date = r.getAs[String]("_id").get
    val count = r.getAs[DBObject]("value").get.getAs[Double]("count").get.toInt
    parseStandardDate(date) -> Seq(count.toString)
  }
}

case class ReportRow(fields: Seq[String]) {
  def toCSV = fields.mkString(",")
}
case class CompleteReport(list: Seq[ReportRow]) {
  def toCSV = (list.map(_.toCSV)).mkString("\n")
}

trait Report {
  lazy val store = inject[MongoEventStore]
  val default_report_size = 10
  val reportName = "report"
  val numFields = 2

  def collectionName = { "report" + reportName }
  def get(startDate: DateTime, endDate: DateTime): CompleteReport
  def get(): CompleteReport = get(currentDateTime.minusDays(default_report_size), currentDateTime)

  def reportBuilder(startDate: LocalDate, endDate: LocalDate, numFields: Int)(reportFields: Map[LocalDate,Seq[String]]) = {
    val firstDay = startDate
    val daysBetween = Days.daysBetween(startDate,endDate).getDays()
    val dayCountList = for(i <- 0 to daysBetween) yield {
      val day = firstDay.plusDays(i)
      val fields = Seq(day.toString) ++ reportFields.getOrElse(day,Seq.fill(numFields-1)("0"))
      ReportRow(fields)
    }
    CompleteReport(dayCountList)
  }
}

trait BasicDailyAggregationReport extends Report {
  def _get(query: DBObject, startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val results = store.mapReduce(EventFamilies.EXTENSION.collection, MongoMapFunc.DATE_COUNT, MongoReduceFunc.BASIC_COUNT, None, Some(query), None).toList
    val builder = reportBuilder(startDate.toLocalDate, endDate.toLocalDate, 2)_
    builder(results map Parsers.dateCountParser toMap)
  }
}

class DailyActiveUniqueUserReport extends Report with Logging {
  override val reportName = "DailyActiveUniqueUser"
  override val numFields = 2

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.GENERIC_USER)
                     .withDateRange(startDate, endDate)
                     .build

    store.mapReduce(EventFamilies.GENERIC_USER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toLocalDate, endDate.toLocalDate, 2)_

    builder(results map Parsers.dateCountParser toMap)
  }
}

class DailyPageLoadReport extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyPageLoadReport"
  override val numFields = 2

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.EXTENSION).withDateRange(startDate, endDate).withEventName("pageLoad").build
    _get(selector, startDate, endDate)
  }
}

class DailySearchQueriesReport extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySearchQueriesReport"
  override val numFields = 2

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("newSearch").build
    _get(selector, startDate, endDate)
  }
}
