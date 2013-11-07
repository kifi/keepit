package com.keepit.common

import java.util.Locale

import org.joda.time.format._
import org.joda.time.{DateTime, DateTimeZone, LocalDate, LocalTime}

import com.google.inject.{ImplementedBy, Singleton}

import play.api.libs.json.JsError
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.{JsValue, Format}

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

  implicit val DEFAULT_DATE_TIME_ZONE = zones.UTC

  // intentionally labeling UTC as "GMT" (see RFCs 2616, 2822)
  // http://stackoverflow.com/questions/1638932/timezone-for-expires-and-last-modified-http-headers
  val HTTP_HEADER_DATETIME_FORMAT =
    DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss 'GMT'").withLocale(Locale.ENGLISH).withZone(zones.UTC)

  val STANDARD_DATETIME_FORMAT =
    new DateTimeFormatterBuilder().append(
      ISODateTimeFormat.dateTime.getPrinter,
      Array[DateTimeParser](
        ISODateTimeFormat.dateTime.getParser,
        DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS Z").getParser))
    .toFormatter.withLocale(Locale.ENGLISH).withZone(DEFAULT_DATE_TIME_ZONE)

  val UTC_DATETIME_FORMAT = STANDARD_DATETIME_FORMAT.withLocale(Locale.ENGLISH).withZone(zones.UTC)
  val STANDARD_DATE_FORMAT = ISODateTimeFormat.date.withLocale(Locale.ENGLISH).withZone(DEFAULT_DATE_TIME_ZONE)

  implicit object DateTimeJsonFormat extends Format[DateTime] {
    def reads(json: JsValue) = try {
      json.asOpt[String] match {
        case Some(timeStr) => JsSuccess(parseStandardTime(timeStr))
        case None => JsSuccess(new DateTime(json.as[Long]))
      }
    } catch {
      case ex: Throwable => JsError(s"Could not deserialize time $json")
    }
    def writes(o: DateTime) = JsString(o.toStandardTimeString)
  }

  implicit object LocalDateJsonFormat extends Format[LocalDate] {
    def reads(json: JsValue) = try {
      JsSuccess(parseStandardDate(json.as[String]))
    } catch {
      case ex: Throwable => JsError(s"Could not deserialize time $json")
    }
    def writes(o: LocalDate) = JsString(o.toStandardDateString)
  }

  def currentDate(implicit zone: DateTimeZone) = new LocalDate(zone)
  def currentDateTime(implicit zone: DateTimeZone) = new DateTime(zone)

  /**
   * Using a clock is similar to injecting a Provider[DateTime] and Provider[LocalDate] with the difference being that
   * it has a bit nicer syntax and is easier to test. Often we want to explicitly control the DateTime provided,
   * for example when testing a repo that sets the update time of an entity. Preferring clock.now() over
   * currentDateTime or injecting Provider[DateTime] will help us avoid these hard to spot bugs.
   */
  @ImplementedBy(classOf[SystemClock])
  trait Clock {
    def getMillis(): Long

    final def today()(implicit zone: DateTimeZone): LocalDate = new LocalDate(getMillis(), zone)
    final def now()(implicit zone: DateTimeZone): DateTime = new DateTime(getMillis(), zone)
  }

  @Singleton
  class SystemClock extends Clock {
    def getMillis(): Long = System.currentTimeMillis()
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
  }

  implicit class RichLocalDate(val date: LocalDate) extends AnyVal {
    def toStandardDateString: String = STANDARD_DATE_FORMAT.print(date)
  }
}
