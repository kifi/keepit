package com.keepit.common.analytics.reports

import org.joda.time._

import com.keepit.common.analytics._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.search.SearchConfigExperiment
import com.mongodb.casbah.Imports._

import play.api.Play.current

object Parsers {
  type ParsedDBObject = (DateTime, Map[String, ValueOrdering])
  def dateCountParser(name: String, r: DBObject, ordering: Int): ParsedDBObject = {
    val date = r.getAs[String]("_id").get
    val count = r.getAs[DBObject]("value").get.getAs[Double]("count").get.toInt
    parseStandardDate(date).toDateTimeAtStartOfDay -> Map(name -> ValueOrdering(count.toString, ordering))
  }
}

case class ValueOrdering(value: String, ordering: Int)

case class ReportRow(date: DateTime, fields: Map[String, ValueOrdering]) { // key -> ordering, value
  def toCSV = STANDARD_DATETIME_FORMAT.print(date) + "," + fields.toSeq.sortWith((a, b) => a._2.ordering < b._2.ordering).map(_._2.value).mkString(",")
}
case class CompleteReport(reportName: String, reportVersion: String, list: Seq[ReportRow], createdAt: DateTime = currentDateTime) extends Logging {
  def toCSV = {
    val columns = list.flatMap{ row =>
      row.fields.toSeq
    }.sortWith((a, b) => a._2.ordering < b._2.ordering).map(_._1).distinct

    log.info(columns mkString ", ")
    val dates = list.map(_.date).sorted

    val keyedList = list.map { row =>
      val f = columns.map { column =>
        row.fields.getOrElse(column, ValueOrdering("", 0)).value
      }
      (row.date, f)
    } toMap

    log.info(keyedList mkString ", ")

    val res = keyedList.toSeq.sortWith((a, b) => a._1.compareTo(b._1) > 0).map(v => v._1.toStandardTimeString + "," + v._2.mkString(",")).mkString("\n")

    ("datetime," + columns.mkString(",") + "\n") + res
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

  def persistenceKey = "%s %s".format(createdAt.toStandardTimeString, reportName)
}

trait Report {
  lazy val store = inject[MongoEventStore]
  val default_report_size = 10
  def reportName = "report"
  val numFields = 2
  val reportVersion: String = "1.0"
  val ordering = 1000

  def collectionName = { "report" + reportName }
  def get(startDate: DateTime, endDate: DateTime): CompleteReport
  def get(): CompleteReport = get(currentDateTime.minusDays(default_report_size), currentDateTime)

  def reportBuilder(startDate: DateTime, endDate: DateTime, numFields: Int)(reportFields: Map[DateTime, Map[String, ValueOrdering]]) = {
    val rows = (reportFields map { row =>
      ReportRow(row._1, row._2)
    } toSeq) sortWith((a,b) => a.date.isAfter(b.date))
    CompleteReport(reportName, reportVersion, rows)
  }
}

trait BasicDailyAggregationReport extends Report {
  override val numFields = 2

  def get(query: DBObject, startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val results = store.mapReduce(EventFamilies.EXTENSION.collection, MongoMapFunc.DATE_COUNT, MongoReduceFunc.BASIC_COUNT, None, Some(query), None).toList
    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_
    builder(results map(Parsers.dateCountParser(reportName + "Count", _, ordering)) toMap)
  }
}

class DailyActiveUniqueUserReport extends Report with Logging {
  override val reportName = "DailyActiveUniqueUser"
  override val numFields = 2
  override val ordering = 10

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.GENERIC_USER)
                     .withDateRange(startDate, endDate)
                     .build

    store.mapReduce(EventFamilies.GENERIC_USER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map(Parsers.dateCountParser(reportName,_, ordering)) toMap)
  }
}

class DailyUniqueDepricatedAddBookmarks extends Report with Logging {
  override val reportName = "DailyUniqueDepricatedAddBookmarks"
  override val numFields = 2
  override val ordering = 20

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.ACCOUNT)
      .withEventName("deprecated_add_bookmarks")
      .withDateRange(startDate, endDate)
      .build

    store.mapReduce(EventFamilies.ACCOUNT.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map(Parsers.dateCountParser(reportName,_, ordering)) toMap)
  }
}

class DailyPageLoadReport extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyPageLoadReport"
  override val ordering = 30

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.EXTENSION).withDateRange(startDate, endDate).withEventName("pageLoad").build
    super.get(selector, startDate, endDate)
  }
}

class DailySearchQueriesReport extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySearchQueriesReport"
  override val ordering = 40

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("newSearch").build
    super.get(selector, startDate, endDate)
  }
}

class DailyGoogleResultClicked extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyGoogleResultClicked"
  override val ordering = 50

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("googleResultClicked").build
    super.get(selector, startDate, endDate)
  }
}

class DailyKifiResultClicked extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyKifiResultClicked"
  override val ordering = 70

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("kifiResultClicked").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderShownByAuto extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySliderShownByAuto"
  override val ordering = 80

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderShown").withMetaData("trigger","auto").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderShownByIcon extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySliderShownByButton"
  override val ordering = 90

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderShown").withMetaData("trigger","button").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderShownByKey extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySliderShownByKey"
  override val ordering = 100

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderShown").withMetaData("trigger","key").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderClosedByIcon extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySliderClosedByButton"
  override val ordering = 110

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderClosed").withMetaData("trigger","button").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderClosedByKey extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySliderClosedByKey"
  override val ordering = 120

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderClosed").withMetaData("trigger","key").build
    super.get(selector, startDate, endDate)
  }
}
class DailySliderClosedByX extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailySliderClosedByX"
  override val ordering = 130

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderClosed").withMetaData("trigger","x").build
    super.get(selector, startDate, endDate)
  }
}

class DailyComment extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyComment"
  override val ordering = 140

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("comment").build
    super.get(selector, startDate, endDate)
  }
}

class DailyMessage extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyMessage"
  override val ordering = 150

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("message").build
    super.get(selector, startDate, endDate)
  }
}

class DailyUnkeep extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyUnkeep"
  override val ordering = 160

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("unkeep").build
    super.get(selector, startDate, endDate)
  }
}

class DailyKeep extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyKeep"
  override val ordering = 170

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("keep").build
    super.get(selector, startDate, endDate)
  }
}

class DailyUsefulPage extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyUsefulPage"
  override val ordering = 180

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("usefulPage").build
    super.get(selector, startDate, endDate)
  }
}

class DailyTotalUsers extends Report with Logging {
  override val reportName = "DailyTotalUsers"
  override val ordering = 190

  def get(startDate: DateTime, endDate: DateTime): CompleteReport = {
    var fields = Seq[ReportRow]()
    inject[Database].readOnly { implicit session =>
      val conn = session.conn
      val st = conn.createStatement()
      val sql =
        """
          select count(*) as sum, CONCAT(YEAR(created_at), "-", MONTH(created_at), "-", DAY(created_at)) as day
          from user
          where created_at > STR_TO_DATE("%s","%%Y-%%m-%%d") and created_at < STR_TO_DATE("%s","%%Y-%%m-%%d") group by day;
        """.format(startDate.toStandardDateString, endDate.toStandardDateString)
      val rs = st.executeQuery(sql)
      while(rs.next) {
        val day = parseStandardDate(rs.getString("day")).toDateTimeAtStartOfDay
        val sum = rs.getInt("sum")
        fields +:= ReportRow(day, Map(reportName -> ValueOrdering(sum.toString, ordering)))
      }
    }
    CompleteReport(reportName = reportName, reportVersion = reportVersion, list = fields)
  }
}

class DailyPrivateKeeps extends Report with Logging {
  override val reportName = "DailyPrivateKeeps"
  override val ordering = 200

  def get(startDate: DateTime, endDate: DateTime): CompleteReport = {
    var fields = Seq[ReportRow]()
    inject[Database].readOnly { implicit session =>
      val conn = session.conn
      val st = conn.createStatement()
      val sql =
        """
          select count(*) as sum, CONCAT(YEAR(created_at), "-", MONTH(created_at), "-", DAY(created_at)) as day
          from bookmark
          where source="HOVER_KEEP" and is_private=true and created_at > STR_TO_DATE("%s","%%Y-%%m-%%d") and created_at < STR_TO_DATE("%s","%%Y-%%m-%%d") group by day;
        """.format(startDate.toStandardDateString, endDate.toStandardDateString)
      val rs = st.executeQuery(sql)
      while(rs.next) {
        val day = parseStandardDate(rs.getString("day")).toDateTimeAtStartOfDay
        val sum = rs.getInt("sum")
        fields +:= ReportRow(day, Map(reportName -> ValueOrdering(sum.toString, ordering)))
      }
    }
    CompleteReport(reportName = reportName, reportVersion = reportVersion, list = fields)
  }
}

class DailyPublicKeeps extends Report with Logging {
  override val reportName = "DailyPublicKeeps"
  override val ordering = 210

  def get(startDate: DateTime, endDate: DateTime): CompleteReport = {
    var fields = Seq[ReportRow]()
    inject[Database].readOnly { implicit session =>
      val conn = session.conn
      val st = conn.createStatement()
      val sql =
        """
          select count(*) as sum, CONCAT(YEAR(created_at), "-", MONTH(created_at), "-", DAY(created_at)) as day
          from bookmark
          where source="HOVER_KEEP" and is_private=false and created_at > STR_TO_DATE("%s","%%Y-%%m-%%d") and created_at < STR_TO_DATE("%s","%%Y-%%m-%%d") group by day;
        """.format(startDate.toStandardDateString, endDate.toStandardDateString)
      val rs = st.executeQuery(sql)
      while(rs.next) {
        val day = parseStandardDate(rs.getString("day")).toDateTimeAtStartOfDay
        val sum = rs.getInt("sum")
        fields +:= ReportRow(day, Map(reportName -> ValueOrdering(sum.toString, ordering)))
      }
    }
    CompleteReport(reportName = reportName, reportVersion = reportVersion, list = fields)
  }
}

class DailyNewThread extends Report with Logging {
  override val reportName = "DailyNewThread"
  override val ordering = 220

  def get(startDate: DateTime, endDate: DateTime): CompleteReport = {
    var fields = Seq[ReportRow]()
    inject[Database].readOnly { implicit session =>
      val conn = session.conn
      val st = conn.createStatement()
      val sql =
        """
          select count(*) as sum, CONCAT(YEAR(created_at), '-', MONTH(created_at), '-', DAY(created_at)) as day
          from comment
          where permissions='message' and parent is null and created_at > STR_TO_DATE("%s","%%Y-%%m-%%d") and created_at < STR_TO_DATE("%s","%%Y-%%m-%%d") group by day;
        """.format(startDate.toStandardDateString, endDate.toStandardDateString)
      val rs = st.executeQuery(sql)
      while(rs.next) {
        val day = parseStandardDate(rs.getString("day")).toDateTimeAtStartOfDay
        val sum = rs.getInt("sum")
        fields +:= ReportRow(day, Map(reportName -> ValueOrdering(sum.toString, ordering)))
      }
    }
    CompleteReport(reportName = reportName, reportVersion = reportVersion, list = fields)
  }
}

class DailyActiveUsers extends ActiveUsersReport(MongoMapFunc.USER_DATE_COUNT,
  Seq("keep", "comment", "message", "kifiResultClicked"))
class WeeklyActiveUsers extends ActiveUsersReport(MongoMapFunc.USER_WEEK_COUNT,
  Seq("keep", "comment", "message", "kifiResultClicked"))
class MonthlyActiveUsers extends ActiveUsersReport(MongoMapFunc.USER_MONTH_COUNT,
  Seq("keep", "comment", "message", "kifiResultClicked"))

class DailyKeepingUsers extends ActiveUsersReport(MongoMapFunc.USER_DATE_COUNT, Seq("keep"))
class WeeklyKeepingUsers extends ActiveUsersReport(MongoMapFunc.USER_WEEK_COUNT, Seq("keep"))
class MonthlyKeepingUsers extends ActiveUsersReport(MongoMapFunc.USER_MONTH_COUNT, Seq("keep"))

class DailyKCMUsers extends ActiveUsersReport(MongoMapFunc.USER_DATE_COUNT, Seq("keep", "comment", "message"))
class WeeklyKCMUsers extends ActiveUsersReport(MongoMapFunc.USER_WEEK_COUNT, Seq("keep", "comment", "message"))
class MonthlyKCMUsers extends ActiveUsersReport(MongoMapFunc.USER_MONTH_COUNT, Seq("keep", "comment", "message"))

class DailyClickingUsers extends ActiveUsersReport(MongoMapFunc.USER_DATE_COUNT, Seq("kifiResultClicked"))
class WeeklyClickingUsers extends ActiveUsersReport(MongoMapFunc.USER_WEEK_COUNT, Seq("kifiResultClicked"))
class MonthlyClickingUsers extends ActiveUsersReport(MongoMapFunc.USER_MONTH_COUNT, Seq("kifiResultClicked"))

sealed abstract class ActiveUsersReport(func: MongoMapFunc, events: Seq[String]) extends Report with Logging {
  override val reportName = getClass.getSimpleName
  override val numFields = 2
  override val ordering = 230

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.GENERIC_USER).withDateRange(startDate, endDate)
        .withEventNameIn(events: _*).build

    store.mapReduce(EventFamilies.GENERIC_USER.collection, func, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toSeq

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2) _

    builder(results map(Parsers.dateCountParser(reportName,_, ordering)) toMap)
  }
}

class DailyUniqueUsersKeeping extends Report with Logging {
  override val reportName = "DailyUniqueUsersKeeping"
  override val numFields = 2
  override val ordering = 230

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("keep").build

    store.mapReduce(EventFamilies.SLIDER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map(Parsers.dateCountParser(reportName,_, ordering)) toMap)
  }
}

class DailyUniqueUsersMessaging extends Report with Logging {
  override val reportName = "DailyUniqueUsersMessaging"
  override val numFields = 2
  override val ordering = 240

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("message").build

    store.mapReduce(EventFamilies.SLIDER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map(Parsers.dateCountParser(reportName,_, ordering)) toMap)
  }
}

class DailyUniqueUsersCommenting extends Report with Logging {
  override val reportName = "DailyUniqueUsersCommenting"
  override val numFields = 2
  override val ordering = 240

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("comment").build

    store.mapReduce(EventFamilies.SLIDER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map(Parsers.dateCountParser(reportName,_, ordering)) toMap)
  }
}

class DailyKifiLoadedReport extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyKifiLoadedReport"
  override val ordering = 250

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("kifiLoaded").build
    super.get(selector, startDate, endDate)
  }
}


class DailyKifiAtLeastOneResult extends BasicDailyAggregationReport with Logging {
  override val reportName = "DailyKifiAtLeastOneResult"
  override val ordering = 260

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("kifiAtLeastOneResult").build
    super.get(selector, startDate, endDate)
  }
}

class DailyDustSettledKifiHadResults(kifiHadResults: Boolean = true) extends BasicDailyAggregationReport with Logging {
  override val reportName = s"DailyDustSettledKifiHad${if (kifiHadResults) "" else "No"}Results"
  override val ordering = 270 + (if (kifiHadResults) 1 else 0)

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.SEARCH)
        .withDateRange(startDate, endDate)
        .withMetaData("kifiHadResults", kifiHadResults)
        .withEventName("dustSettled").build
    super.get(selector, startDate, endDate)
  }
}

trait DailyByExperiment extends BasicDailyAggregationReport with Logging {
  def eventName: String
  def experiment: Option[SearchConfigExperiment]

  override lazy val reportName = s"Daily${eventName.capitalize} ${experiment.map(_.description).getOrElse("Default")}"
  def baseSelector(startDate: DateTime, endDate: DateTime): MongoSelector =
    MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate)

  def get(startDate: DateTime, endDate: DateTime): CompleteReport = {
    val sel = baseSelector(startDate, endDate).withEventName(eventName)
    val selectorWithExperimentId = experiment.map { e =>
      sel.withMetaData("experimentId", e.id.get.id.toInt)
    }.getOrElse {
      sel.withMetaData("experimentId", "null")
    }.build
    super.get(selectorWithExperimentId, startDate, endDate)
  }
  override def hashCode() = (reportName, eventName, experiment.flatMap(_.id)).hashCode()
  override def equals(other: Any) = other match {
    case that: DailyByExperiment =>
      (reportName, eventName, experiment.flatMap(_.id)) ==
          (that.reportName, that.eventName, that.experiment.flatMap(_.id))
    case _ => false
  }
}

class DailyDustSettledKifiHadResultsByExperiment(val experiment: Option[SearchConfigExperiment],
    val kifiHadResults: Boolean = true) extends DailyByExperiment {
  override val eventName = "dustSettled"
  override lazy val reportName = s"DailyDustSettledKifiHad${if (kifiHadResults) "" else "No"}Results " +
    s"${experiment.map(_.description).getOrElse("Default")}"
  override val ordering = 4000 + experiment.map(_.id.get.id.toInt).getOrElse(0)
  override def baseSelector(startDate: DateTime, endDate: DateTime): MongoSelector =
    MongoSelector(EventFamilies.SEARCH)
      .withDateRange(startDate, endDate)
      .withMetaData("kifiHadResults", kifiHadResults)
}

class DailyKifiAtLeastOneResultByExperiment(val experiment: Option[SearchConfigExperiment]) extends DailyByExperiment {
  override val eventName = "kifiAtLeastOneResult"
  override val ordering = 1000 + experiment.map(_.id.get.id.toInt).getOrElse(0)
}

class DailyGoogleResultClickedByExperiment(val experiment: Option[SearchConfigExperiment]) extends DailyByExperiment {
  override val eventName = "googleResultClicked"
  override val ordering = 2000 + experiment.map(_.id.get.id.toInt).getOrElse(0)
}

class DailyKifiResultClickedByExperiment(val experiment: Option[SearchConfigExperiment]) extends DailyByExperiment {
  override val eventName = "kifiResultClicked"
  override val ordering = 3000 + experiment.map(_.id.get.id.toInt).getOrElse(0)
}

