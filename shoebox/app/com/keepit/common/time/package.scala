package com.keepit.common

import com.google.inject.Singleton
import java.util.Locale
import org.joda.time.{DateTime, DateTimeZone, LocalDate, LocalTime}
import org.joda.time.format.DateTimeFormat
import play.api.libs.json.JsString

package object time {
  object zones {
    /**
     * Eastern Standard/Daylight Time.
     */
    val ET = DateTimeZone.forID("America/New_York")

    /**
     * Pacific Standard/Daylight Time.
     */
    val PT = DateTimeZone.forID("America/Los_Angeles")

    /**
     * Coordinated Universal Time.
     */
    val UTC = DateTimeZone.UTC
  }

  implicit val DEFAULT_DATE_TIME_ZONE = zones.PT

  // intentionally labeling UTC as "GMT" (see RFCs 2616, 2822)
  // http://stackoverflow.com/questions/1638932/timezone-for-expires-and-last-modified-http-headers
  val HTTP_HEADER_DATETIME_FORMAT = DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss 'GMT'")
                                             .withLocale(Locale.ENGLISH)
                                             .withZone(zones.UTC)
  val STANDARD_DATETIME_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS Z")
                                             .withLocale(Locale.ENGLISH)
                                             .withZone(zones.PT)
  val STANDARD_DATE_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd")
                                             .withLocale(Locale.ENGLISH)
                                             .withZone(zones.PT)

  def currentDate(implicit zone: DateTimeZone) = new LocalDate(zone)
  def currentDateTime(implicit zone: DateTimeZone) = new DateTime(zone)

  @Singleton
  class Clock() {
    val clockZone: DateTimeZone = DEFAULT_DATE_TIME_ZONE
    def currentDate: LocalDate = new LocalDate(clockZone)
    def currentDateTime: DateTime = new DateTime(clockZone)
  }

  implicit val localDateOrdering = new Ordering[LocalDate] {
    def compare(a: LocalDate, b: LocalDate) = a.compareTo(b)
  }

  implicit val dateTimeOrdering = new Ordering[DateTime] {
    def compare(a: DateTime, b: DateTime) = a.compareTo(b)
  }

  implicit class DateTimeConverter(val time: Long) extends AnyVal {
    def toDateTime(implicit zone: DateTimeZone): DateTime = new DateTime(time, zone)
  }

  implicit def dateToDateTimeConverter(date: java.util.Date) = new DateTimeConverter(date.getTime)

  implicit def sqlDateToDateTimeConverter(date: java.sql.Date) = new DateTimeConverter(date.getTime)

  implicit def sqlTimeToDateTimeConverter(time: java.sql.Time) = new DateTimeConverter(time.getTime)

  implicit def sqlTimestampToDateTimeConverter(ts: java.sql.Timestamp) = new DateTimeConverter(ts.getTime)

  def parseStandardTime(timeString: String) = STANDARD_DATETIME_FORMAT.parseDateTime(timeString)
  def parseStandardDate(timeString: String) = STANDARD_DATE_FORMAT.parseLocalDate(timeString)

  lazy val START_OF_TIME = parseStandardTime("0000-01-01 00:00:00.000 -0800")
  lazy val END_OF_TIME = parseStandardTime("9999-01-01 00:00:00.000 -0800")

  implicit class RichDateTime(val date: DateTime) extends AnyVal {
    def toLocalDateInZone(implicit zone: DateTimeZone): LocalDate = date.withZone(zone).toLocalDate
    def toLocalTimeInZone(implicit zone: DateTimeZone): LocalTime = date.withZone(zone).toLocalTime
    def toHttpHeaderString: String = HTTP_HEADER_DATETIME_FORMAT.print(date)
    def toStandardTimeString: String = STANDARD_DATETIME_FORMAT.print(date)
    def toStandardDateString: String = STANDARD_DATE_FORMAT.print(date)

    def isSameDay(otherDate: DateTime)(implicit zone: DateTimeZone): Boolean = {
      val z = date.withZone(zone)
      val z2 = otherDate.withZone(zone)
      z.getDayOfYear == z2.getDayOfYear && z.getYear == z2.getYear
    }

    def isSameDay(ld: LocalDate)(implicit zone: DateTimeZone): Boolean = ld == date.withZone(zone).toLocalDate

    def format = HTTP_HEADER_DATETIME_FORMAT.print(date)
  }

  implicit class RichTimeZone(val zone: DateTimeZone) extends AnyVal {
    def localDateFor(d: DateTime): LocalDate = d.withZone(zone).toLocalDate
    def localTimeFor(d: DateTime): LocalTime = d.withZone(zone).toLocalTime
  }

  implicit class RichLocalDate(val ld: LocalDate) extends AnyVal {
    def isSameDay(d: DateTime)(implicit zone: DateTimeZone) = ld == d.withZone(zone).toLocalDate
    def toJson: JsString = JsString(STANDARD_DATE_FORMAT.print(ld))
  }
}
