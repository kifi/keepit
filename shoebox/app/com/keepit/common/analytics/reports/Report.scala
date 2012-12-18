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
case class CompleteReport(reportName: String, reportVersion: String, list: Seq[ReportRow], createdAt: DateTime = currentDateTime) extends Logging {
  def toCSV = {
    val columns = (for {
      row <- list
      field <- row.fields
    } yield field._1).distinct.sorted
    val dates = list.map(_.date).sorted

    val keyedList = list map { row =>
      val f = columns map { column =>
        row.fields.getOrElse(column,"")
      }
      (row.date, f)
    } toMap

    val duration = new Duration(dates.headOption.getOrElse(currentDateTime.minusDays(30)), dates.tail.headOption.getOrElse(currentDateTime))
    val startDate = dates.head
    val endDate = dates.last
    var d = startDate
    var csvList = Seq[String]()
    while(!d.isAfter(endDate)) {
      csvList = (d.toStandardTimeString + "," + (keyedList.getOrElse(d,Seq.fill(columns.length-1)(",")).mkString(","))) +: csvList
      d = d.plus(duration)
    }
    csvList = ("datetime," + columns.mkString(",")) +: csvList
    csvList.mkString("\n")
  }
  def +(that: CompleteReport) = {
    // Challenge: rewrite to be functional and use only immutable objects
    val thatIndexed = that.list.toIndexedSeq
    var newList = scala.collection.mutable.Seq(that.list:_*)
    val thatDates = that.list.map(_.date).toIndexedSeq
    this.list map { thisRow =>
      val pos = thatDates.indexOf(thisRow.date)
      if(pos != -1) {
        val newFields = thisRow.fields ++ thatIndexed(pos).fields // that's fields replace this's fields of the same date
        newList.update(newList.indexOf(thatIndexed(pos)), ReportRow(thisRow.date, newFields))
      }
      else {
        newList = thisRow +: newList
      }
    }
    val newName = if(reportName == "") {
      that.reportName
    } else {
      if(that.reportName == "") reportName
      else "%s,%s".format(reportName,that.reportName)
    }

    CompleteReport(newName, reportVersion, newList.sortWith((a,b) => a.date.isAfter(b.date)))
  }

  def persist = {
    inject[ReportStore] += ("%s %s".format(createdAt.toStandardTimeString, reportName) -> this)
  }

}

trait Report {
  lazy val store = inject[MongoEventStore]
  val default_report_size = 10
  val reportName = "report"
  val numFields = 2
  val reportVersion: String = "1.0"

  def collectionName = { "report" + reportName }
  def get(startDate: DateTime, endDate: DateTime): CompleteReport
  def get(): CompleteReport = get(currentDateTime.minusDays(default_report_size), currentDateTime)

  def reportBuilder(startDate: DateTime, endDate: DateTime, numFields: Int)(reportFields: Map[DateTime,Map[String,String]]) = {
    val rows = (reportFields map { row =>
      ReportRow(row._1, row._2)
    } toSeq) sortWith((a,b) => a.date.isAfter(b.date))
    CompleteReport(reportName, reportVersion, rows)
  }
}

trait BasicDailyAggregationReport extends Report {
  override val numFields = 2

  def _get(query: DBObject, startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val results = store.mapReduce(EventFamilies.EXTENSION.collection, MongoMapFunc.DATE_COUNT, MongoReduceFunc.BASIC_COUNT, None, Some(query), None).toList
    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_
    builder(results map(Parsers.dateCountParser(reportName + "Count",_)) toMap)
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

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.EXTENSION).withDateRange(startDate, endDate).withEventName("pageLoad").build
    _get(selector, startDate, endDate)
  }
}

class DailySearchQueriesReport extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySearchQueriesReport"

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("newSearch").build
    _get(selector, startDate, endDate)
  }
}

class DailyGoogleResultClicked extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyGoogleResultClicked"

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("googleResultClicked").build
    _get(selector, startDate, endDate)
  }
}

class DailyKifiResultClicked extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyKifiResultClicked"

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("kifiResultClicked").build
    _get(selector, startDate, endDate)
  }
}


