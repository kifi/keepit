package com.keepit.common.analytics.reports

import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.analytics._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.inject._
import play.api.Play.current
import org.joda.time._

trait Report {
  lazy val store = inject[MongoEventStore]
  val default_report_size = 10
}

case class ReportResult(fields: String*)
case class CompleteReport(list: Seq[ReportResult])

class DailyActiveUniqueUserReport extends Report {
  def get(): CompleteReport = {
    get(currentDateTime.minusDays(default_report_size), currentDateTime)
  }

  def get(startDate: DateTime, endDate: DateTime): CompleteReport  = {
    val selector = MongoSelector(EventFamilies.GENERIC_USER)
                     .withDateRange(startDate, endDate)
                     .build

    val result = store.countGroup(EventFamilies.GENERIC_USER, selector, MongoMapFunc.USER_DATE)

    val dates = result map { res =>
      parseStandardDate((res \ "date").as[String])
    }

    val daysCount = dates.foldLeft(Map[LocalDate,Int]().withDefaultValue(0)){(m, d) => m + (d -> (1 + m(d)))}

    val daysBetween = Days.daysBetween(startDate,endDate).getDays()
    val firstDay = startDate.toLocalDate
    val dayCountList = for(i <- 0 to daysBetween) yield {
      val day = firstDay.plusDays(i)
      ReportResult(day.toString, daysCount(day).toString)
    }
    CompleteReport(dayCountList)
  }
}
