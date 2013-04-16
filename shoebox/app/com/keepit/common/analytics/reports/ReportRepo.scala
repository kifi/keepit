package com.keepit.common.analytics.reports

import org.joda.time._

import com.google.inject.Inject
import com.keepit.common.analytics._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.SearchConfigExperiment
import com.mongodb.casbah.Imports._
import com.keepit.serializer.EventSerializer

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

case class Report(reportName: String, reportVersion: String, list: Seq[ReportRow], createdAt: DateTime = currentDateTime) extends Logging {
  def toCSV = {
    val columns = list.flatMap { row =>
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
  def +(that: Report) = {
    // Challenge: rewrite to be functional and use only immutable objects
    val thatIndexed = that.list.toIndexedSeq
    var newList = scala.collection.mutable.Seq(that.list: _*)
    val thatDates = that.list.map(_.date).toIndexedSeq
    Report.this.list map { thisRow =>
      val pos = thatDates.indexOf(thisRow.date)
      if (pos != -1) {
        val newFields = thisRow.fields ++ thatIndexed(pos).fields // that's fields replace this's fields of the same date
        newList.update(newList.indexOf(thatIndexed(pos)), ReportRow(thisRow.date, newFields))
      } else {
        newList = thisRow +: newList
      }
    }
    val newName = if (reportName == "") {
      that.reportName
    } else {
      if (that.reportName == "") reportName
      else "%s,%s".format(reportName, that.reportName)
    }

    Report(newName, reportVersion, newList.sortWith((a, b) => a.date.isAfter(b.date)))
  }

  def persistenceKey = "%s %s".format(createdAt.toStandardTimeString, reportName)
}

abstract class ReportRepo @Inject() (store: MongoEventStore) {
  val default_report_size = 10
  def reportName = "report"
  val numFields = 2
  val reportVersion: String = "1.0"
  val ordering = 1000

  def collectionName = { "report" + reportName }
  def get(startDate: DateTime, endDate: DateTime): Report
  def get(): Report = get(currentDateTime.minusDays(default_report_size), currentDateTime)

  def reportBuilder(startDate: DateTime, endDate: DateTime, numFields: Int)(reportFields: Map[DateTime, Map[String, ValueOrdering]]) = {
    val rows = (reportFields map { row =>
      ReportRow(row._1, row._2)
    } toSeq) sortWith ((a, b) => a.date.isAfter(b.date))
    Report(reportName, reportVersion, rows)
  }
}

abstract class BasicDailyAggregationReportRepo @Inject() (store: MongoEventStore) extends ReportRepo(store) {
  override val numFields = 2

  def get(query: DBObject, startDate: DateTime, endDate: DateTime): Report = {
    val results = store.mapReduce(EventFamilies.EXTENSION.collection, MongoMapFunc.DATE_COUNT, MongoReduceFunc.BASIC_COUNT, None, Some(query), None).toList
    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_
    builder(results map (Parsers.dateCountParser(reportName + "Count", _, ordering)) toMap)
  }
}

class DailyActiveUniqueUserReportRepo @Inject() (store: MongoEventStore) extends ReportRepo(store) with Logging {
  override val reportName = "DailyActiveUniqueUser"
  override val numFields = 2
  override val ordering = 10

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.GENERIC_USER)
      .withDateRange(startDate, endDate)
      .build

    store.mapReduce(EventFamilies.GENERIC_USER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map (Parsers.dateCountParser(reportName, _, ordering)) toMap)
  }
}

class DailyUniqueDepricatedAddBookmarksRepo @Inject() (store: MongoEventStore) extends ReportRepo(store) with Logging {
  override val reportName = "DailyUniqueDepricatedAddBookmarks"
  override val numFields = 2
  override val ordering = 20

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.ACCOUNT)
      .withEventName("deprecated_add_bookmarks")
      .withDateRange(startDate, endDate)
      .build

    store.mapReduce(EventFamilies.ACCOUNT.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map (Parsers.dateCountParser(reportName, _, ordering)) toMap)
  }
}

class DailyPageLoadReportRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyPageLoadReport"
  override val ordering = 30

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.EXTENSION).withDateRange(startDate, endDate).withEventName("pageLoad").build
    super.get(selector, startDate, endDate)
  }
}

class DailySearchQueriesReportRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailySearchQueriesReport"
  override val ordering = 40

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("newSearch").build
    super.get(selector, startDate, endDate)
  }
}

class DailyGoogleResultClickedRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyGoogleResultClicked"
  override val ordering = 50

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("googleResultClicked").build
    super.get(selector, startDate, endDate)
  }
}

class DailyKifiResultClickedRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyKifiResultClicked"
  override val ordering = 70

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("kifiResultClicked").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderShownByAutoRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailySliderShownByAuto"
  override val ordering = 80

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderShown").withMetaData("trigger", "auto").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderShownByIconRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailySliderShownByButton"
  override val ordering = 90

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderShown").withMetaData("trigger", "button").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderShownByKeyRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailySliderShownByKey"
  override val ordering = 100

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderShown").withMetaData("trigger", "key").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderClosedByIconRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailySliderClosedByButton"
  override val ordering = 110

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderClosed").withMetaData("trigger", "button").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderClosedByKeyRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailySliderClosedByKey"
  override val ordering = 120

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderClosed").withMetaData("trigger", "key").build
    super.get(selector, startDate, endDate)
  }
}

class DailySliderClosedByXRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailySliderClosedByX"
  override val ordering = 130

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("sliderClosed").withMetaData("trigger", "x").build
    super.get(selector, startDate, endDate)
  }
}

class DailyCommentRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyComment"
  override val ordering = 140

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("comment").build
    super.get(selector, startDate, endDate)
  }
}

class DailyMessageRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyMessage"
  override val ordering = 150

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("message").build
    super.get(selector, startDate, endDate)
  }
}

class DailyUnkeepRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyUnkeep"
  override val ordering = 160

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("unkeep").build
    super.get(selector, startDate, endDate)
  }
}

class DailyKeepRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyKeep"
  override val ordering = 170

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("keep").build
    super.get(selector, startDate, endDate)
  }
}

class DailyUsefulPageRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyUsefulPage"
  override val ordering = 180

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("usefulPage").build
    super.get(selector, startDate, endDate)
  }
}

class DailyTotalUsersRepo @Inject() (store: MongoEventStore, db: Database) extends ReportRepo(store) with Logging {
  override val reportName = "DailyTotalUsers"
  override val ordering = 190

  def get(startDate: DateTime, endDate: DateTime): Report = {
    var fields = Seq[ReportRow]()
    db.readOnly { implicit session =>
      val conn = session.conn
      val st = conn.createStatement()
      val sql =
        s"""
          select count(*) as sum, CONCAT(YEAR(created_at), "-", MONTH(created_at), "-", DAY(created_at)) as day
          from user
          where created_at > ${db.dialect.day(startDate)} and
                created_at < ${db.dialect.day(endDate)} group by day;
        """
      val rs = st.executeQuery(sql)
      while (rs.next) {
        val day = parseStandardDate(rs.getString("day")).toDateTimeAtStartOfDay
        val sum = rs.getInt("sum")
        fields +:= ReportRow(day, Map(reportName -> ValueOrdering(sum.toString, ordering)))
      }
    }
    Report(reportName = reportName, reportVersion = reportVersion, list = fields)
  }
}

class DailyPrivateKeepsRepo @Inject() (store: MongoEventStore, db: Database) extends ReportRepo(store) with Logging {
  override val reportName = "DailyPrivateKeeps"
  override val ordering = 200

  def get(startDate: DateTime, endDate: DateTime): Report = {
    var fields = Seq[ReportRow]()
    db.readOnly { implicit session =>
      val conn = session.conn
      val st = conn.createStatement()
      val sql =
        s"""
          select count(*) as sum, CONCAT(YEAR(created_at), "-", MONTH(created_at), "-", DAY(created_at)) as day
          from bookmark
          where source="HOVER_KEEP" and is_private=true and
          created_at > ${db.dialect.day(startDate)} and
          created_at < ${db.dialect.day(endDate)} group by day;
        """
      val rs = st.executeQuery(sql)
      while (rs.next) {
        val day = parseStandardDate(rs.getString("day")).toDateTimeAtStartOfDay
        val sum = rs.getInt("sum")
        fields +:= ReportRow(day, Map(reportName -> ValueOrdering(sum.toString, ordering)))
      }
    }
    Report(reportName = reportName, reportVersion = reportVersion, list = fields)
  }
}

class DailyPublicKeepsRepo @Inject() (store: MongoEventStore, db: Database) extends ReportRepo(store) with Logging {
  override val reportName = "DailyPublicKeeps"
  override val ordering = 210

  def get(startDate: DateTime, endDate: DateTime): Report = {
    var fields = Seq[ReportRow]()
    db.readOnly { implicit session =>
      val conn = session.conn
      val st = conn.createStatement()
      val sql =
        s"""
          select count(*) as sum, CONCAT(YEAR(created_at), "-", MONTH(created_at), "-", DAY(created_at)) as day
          from bookmark
          where source="HOVER_KEEP" and is_private=false and
          created_at > ${db.dialect.day(startDate)} and
          created_at < ${db.dialect.day(endDate)} group by day;
        """
      val rs = st.executeQuery(sql)
      while (rs.next) {
        val day = parseStandardDate(rs.getString("day")).toDateTimeAtStartOfDay
        val sum = rs.getInt("sum")
        fields +:= ReportRow(day, Map(reportName -> ValueOrdering(sum.toString, ordering)))
      }
    }
    Report(reportName = reportName, reportVersion = reportVersion, list = fields)
  }
}

class DailyNewThreadRepo @Inject() (store: MongoEventStore, db: Database) extends ReportRepo(store) with Logging {
  override val reportName = "DailyNewThread"
  override val ordering = 220

  def get(startDate: DateTime, endDate: DateTime): Report = {
    var fields = Seq[ReportRow]()
    db.readOnly { implicit session =>
      val conn = session.conn
      val st = conn.createStatement()
      val sql =
        s"""
          select count(*) as sum, CONCAT(YEAR(created_at), '-', MONTH(created_at), '-', DAY(created_at)) as day
          from comment
          where permissions='message' and parent is null and
          created_at > ${db.dialect.day(startDate)} and
          created_at < ${db.dialect.day(endDate)} group by day;
        """
      val rs = st.executeQuery(sql)
      while (rs.next) {
        val day = parseStandardDate(rs.getString("day")).toDateTimeAtStartOfDay
        val sum = rs.getInt("sum")
        fields +:= ReportRow(day, Map(reportName -> ValueOrdering(sum.toString, ordering)))
      }
    }
    Report(reportName = reportName, reportVersion = reportVersion, list = fields)
  }
}

class DailyActiveUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_DATE_COUNT,
  Seq("keep", "comment", "message", "kifiResultClicked"))
class WeeklyActiveUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_WEEK_COUNT,
  Seq("keep", "comment", "message", "kifiResultClicked"))
class MonthlyActiveUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_MONTH_COUNT,
  Seq("keep", "comment", "message", "kifiResultClicked"))

class DailyKeepingUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_DATE_COUNT, Seq("keep"))
class WeeklyKeepingUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_WEEK_COUNT, Seq("keep"))
class MonthlyKeepingUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_MONTH_COUNT, Seq("keep"))

class DailyKCMUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_DATE_COUNT, Seq("keep", "comment", "message"))
class WeeklyKCMUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_WEEK_COUNT, Seq("keep", "comment", "message"))
class MonthlyKCMUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_MONTH_COUNT, Seq("keep", "comment", "message"))

class DailyClickingUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_DATE_COUNT, Seq("kifiResultClicked"))
class WeeklyClickingUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_WEEK_COUNT, Seq("kifiResultClicked"))
class MonthlyClickingUsersRepo @Inject() (store: MongoEventStore) extends ActiveUsersReportRepo(store, MongoMapFunc.USER_MONTH_COUNT, Seq("kifiResultClicked"))

sealed abstract class ActiveUsersReportRepo(store: MongoEventStore, func: MongoMapFunc, events: Seq[String]) extends ReportRepo(store) with Logging {
  override val reportName = getClass.getSimpleName
  override val numFields = 2
  override val ordering = 230

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.GENERIC_USER).withDateRange(startDate, endDate)
      .withEventNameIn(events: _*).build

    store.mapReduce(EventFamilies.GENERIC_USER.collection, func, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toSeq

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2) _

    builder(results map (Parsers.dateCountParser(reportName, _, ordering)) toMap)
  }
}

class DailyUniqueUsersKeepingRepo @Inject() (store: MongoEventStore) extends ReportRepo(store) with Logging {
  override val reportName = "DailyUniqueUsersKeeping"
  override val numFields = 2
  override val ordering = 230

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("keep").build

    store.mapReduce(EventFamilies.SLIDER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map (Parsers.dateCountParser(reportName, _, ordering)) toMap)
  }
}

class DailyUniqueUsersMessagingRepo @Inject() (store: MongoEventStore) extends ReportRepo(store) with Logging {
  override val reportName = "DailyUniqueUsersMessaging"
  override val numFields = 2
  override val ordering = 240

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("message").build

    store.mapReduce(EventFamilies.SLIDER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map (Parsers.dateCountParser(reportName, _, ordering)) toMap)
  }
}

class DailyUniqueUsersCommentingRepo @Inject() (store: MongoEventStore) extends ReportRepo(store) with Logging {
  override val reportName = "DailyUniqueUsersCommenting"
  override val numFields = 2
  override val ordering = 240

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SLIDER).withDateRange(startDate, endDate).withEventName("comment").build

    store.mapReduce(EventFamilies.SLIDER.collection, MongoMapFunc.USER_DATE_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), Some(selector), None)
    store.mapReduce(collectionName, MongoMapFunc.KEY_DAY_COUNT, MongoReduceFunc.BASIC_COUNT, Some(collectionName), None, None)

    val resultsSelector = MongoSelector(EventFamilies.GENERIC_USER)
    val results = store.find(collectionName, resultsSelector).toList

    val builder = reportBuilder(startDate.toDateTime, endDate.toDateTime, 2)_

    builder(results map (Parsers.dateCountParser(reportName, _, ordering)) toMap)
  }
}

class DailyKifiLoadedReportRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyKifiLoadedReport"
  override val ordering = 250

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("kifiLoaded").build
    super.get(selector, startDate, endDate)
  }
}

class DailyKifiAtLeastOneResultRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = "DailyKifiAtLeastOneResult"
  override val ordering = 260

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate).withEventName("kifiAtLeastOneResult").build
    super.get(selector, startDate, endDate)
  }
}

class DailyDustSettledKifiHadNoResultsRepo @Inject() (store: MongoEventStore) extends DailyDustSettledKifiMayHadResultsRepo(store, false)
class DailyDustSettledKifiHadResultsRepo @Inject() (store: MongoEventStore) extends DailyDustSettledKifiMayHadResultsRepo(store, true)

abstract class DailyDustSettledKifiMayHadResultsRepo (store: MongoEventStore, kifiHadResults: Boolean) extends BasicDailyAggregationReportRepo(store) with Logging {
  override val reportName = s"DailyDustSettledKifiHad${if (kifiHadResults) "" else "No"}Results"
  override val ordering = 270 + (if (kifiHadResults) 1 else 0)

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SEARCH)
      .withDateRange(startDate, endDate)
      .withMetaData("kifiHadResults", kifiHadResults)
      .withEventName("dustSettled").build
    super.get(selector, startDate, endDate)
  }
}

abstract class DailyByExperimentRepo @Inject() (store: MongoEventStore) extends BasicDailyAggregationReportRepo(store) with Logging {
  def eventName: String
  def experiment: Option[SearchConfigExperiment]

  override lazy val reportName = s"Daily${eventName.capitalize} ${experiment.map(_.description).getOrElse("Default")}"
  def baseSelector(startDate: DateTime, endDate: DateTime): MongoSelector =
    MongoSelector(EventFamilies.SEARCH).withDateRange(startDate, endDate)

  def get(startDate: DateTime, endDate: DateTime): Report = {
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
    case that: DailyByExperimentRepo =>
      (reportName, eventName, experiment.flatMap(_.id)) ==
        (that.reportName, that.eventName, that.experiment.flatMap(_.id))
    case _ => false
  }
}

class DailyDustSettledKifiHadResultsByExperimentRepo @Inject() (store: MongoEventStore, val experiment: Option[SearchConfigExperiment],
  val kifiHadResults: Boolean = true) extends DailyByExperimentRepo(store) {
  override val eventName = "dustSettled"
  override lazy val reportName = s"DailyDustSettledKifiHad${if (kifiHadResults) "" else "No"}Results " +
    s"${experiment.map(_.description).getOrElse("Default")}"
  override val ordering = 4000 + experiment.map(_.id.get.id.toInt).getOrElse(0)
  override def baseSelector(startDate: DateTime, endDate: DateTime): MongoSelector =
    MongoSelector(EventFamilies.SEARCH)
      .withDateRange(startDate, endDate)
      .withMetaData("kifiHadResults", kifiHadResults)
}

class DailyKifiAtLeastOneResultByExperimentRepo @Inject() (store: MongoEventStore, val experiment: Option[SearchConfigExperiment]) extends DailyByExperimentRepo(store) {
  override val eventName = "kifiAtLeastOneResult"
  override val ordering = 1000 + experiment.map(_.id.get.id.toInt).getOrElse(0)
}

class DailyGoogleResultClickedByExperimentRepo @Inject() (store: MongoEventStore, val experiment: Option[SearchConfigExperiment]) extends DailyByExperimentRepo(store) {
  override val eventName = "googleResultClicked"
  override val ordering = 2000 + experiment.map(_.id.get.id.toInt).getOrElse(0)
}

class DailyKifiResultClickedByExperimentRepo @Inject() (store: MongoEventStore, val experiment: Option[SearchConfigExperiment]) extends DailyByExperimentRepo(store) {
  override val eventName = "kifiResultClicked"
  override val ordering = 3000 + experiment.map(_.id.get.id.toInt).getOrElse(0)
}

class DailySearchStatisticsReportRepo @Inject() (store: MongoEventStore) extends ReportRepo(store) with Logging {
  override val reportName = "DailySearchStatistics"

  def get(startDate: DateTime, endDate: DateTime): Report = {
    val selector = MongoSelector(EventFamilies.SERVER_SEARCH).withEventName("search_statistics")
    val cursor = store.find(selector)
    val rows = cursor.map { iter =>
      val data = EventSerializer.eventSerializer.mongoReads(iter).get
      val dateTime = data.createdAt
      val meta = data.metaData.metaData
      val uuid = (meta \ "queryUUID").asOpt[String].getOrElse("")
      val variance = (meta \ "svVariance").asOpt[Double].getOrElse(-1.0).toString
      val kifiClicks = (meta \ "kifiResultsClicked").asOpt[Int].getOrElse(-1).toString
      val googleClicks = (meta \ "googleResultsClicked").asOpt[Int].getOrElse(-1).toString
      ReportRow(dateTime, Map(
        "queryUUID" -> ValueOrdering(uuid, 10),
        "svVariance" -> ValueOrdering(variance, 20),
        "kifiClicks" -> ValueOrdering(kifiClicks, 30),
        "googleClicks" -> ValueOrdering(googleClicks, 40)))
    }.toList
    Report(reportName = reportName, reportVersion = reportVersion, list = rows)
  }
}
