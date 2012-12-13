package com.keepit.common.analytics.reports

import play.api.libs.json._
import com.keepit.common.analytics._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.inject._
import play.api.Play.current
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.mongodb.casbah.Imports._
import scala.util.Random
import org.joda.time._

object Parsers {
  type ParsedDBObject = (DateTime,Map[String,String])
  def dateCountParser(name: String, r: DBObject): ParsedDBObject = {
    val date = r.getAs[String]("_id").get
    val count = r.getAs[DBObject]("value").get.getAs[Double]("count").get.toInt
    parseStandardDate(date).toDateTimeAtStartOfDay -> Map(name -> count.toString)
  }
}

case class ReportRow(date: DateTime, fields: Map[String, String]) {

  def toCSV = STANDARD_DATETIME_FORMAT.print(date) + "," + fields.mkString(",")
}
case class CompleteReport(list: Seq[ReportRow]) {
  private lazy val dates = list map (_.date)
  def toCSV = (list.map(_.toCSV)).mkString("\n")
  def +(that: CompleteReport) = {
    // Mutability and imperative loops for efficiency
    dates map { thisDate =>
      var i = 0
      while(i < that.list.length) {
        if(that.list(i).date.isAfter(thisDate)) {

        }
        i = i+1
      }
    }
  }

}

trait Report {
  lazy val store = inject[MongoEventStore]
  val default_report_size = 10
  val reportName = "report"
  val numFields = 2

  def collectionName = { "report" + reportName }
  def get(startDate: DateTime, endDate: DateTime): CompleteReport
  def get(): CompleteReport = get(currentDateTime.minusDays(default_report_size), currentDateTime)

  def reportBuilder(startDate: DateTime, endDate: DateTime, numFields: Int)(reportFields: Map[DateTime,Map[String,String]]) = {
    val rows = (reportFields map { row =>
      ReportRow(row._1, row._2)
    } toSeq) sortWith((a,b) => a.date.isBefore(b.date))
    CompleteReport(rows)
  }
}

trait BasicDailyAggregationReport extends Report {
  def _get(name: String, query: DBObject, startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val results = store.mapReduce(EventFamilies.EXTENSION.collection, MongoMapFunc.DATE_COUNT, MongoReduceFunc.BASIC_COUNT, None, Some(query), None).toList
    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_
    builder(results map(Parsers.dateCountParser(name,_)) toMap)
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

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map(Parsers.dateCountParser("DailyActiveUniqueUserCount",_)) toMap)
  }
}

class DailyPageLoadReport extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyPageLoadReport"
  override val numFields = 2

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.EXTENSION).withDateRange(startDate, endDate).withEventName("pageLoad").build
    _get("DailyPageLoadReportCount",selector, startDate, endDate)
  }
}

class DailySearchQueriesReport extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySearchQueriesReport"
  override val numFields = 2

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("newSearch").build
    _get("DailySearchQueriesReportCount",selector, startDate, endDate)
  }
}
