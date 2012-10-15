package com.keepit.common

import java.util.Locale
import org.joda.time.{DateTime, DateTimeZone, LocalDate, LocalTime}
import org.joda.time.format.DateTimeFormat

package object time {
  object zones {
    /**
     * Eastern Standard Time.
     */
    val ET = DateTimeZone.forID("America/New_York")
  
    /**
     * Pacific Standard Time.
     */
    val PT = DateTimeZone.forID("America/Los_Angeles")
  
    /**
     * Greenwich Mean Time. TODO: Is this supposed to be UTC? GMT is so last century.
     */
    val GMT = DateTimeZone.forID("GMT")
  }
  
  implicit val DEFAULT_DATE_TIME_ZONE = zones.PT
  
  // rfc2822-compatible format for representing times in http headers
  val HEADER_DATETIME_FORMAT = DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z")
                                             .withLocale(Locale.ENGLISH)
                                             .withZone(zones.GMT)
  val STANDARD_DATETIME_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss Z")
                                             .withLocale(Locale.ENGLISH)
                                             .withZone(zones.PT)
  
                                             
  def currentDateTime(implicit zone: DateTimeZone) = new DateTime(zone)
  
  class DateTimeConverter(time: Long, zone: DateTimeZone) {
    def toDateTime: DateTime = new DateTime(time, zone)
  }
  
  implicit def dateToDateTimeConverter(date: java.util.Date)(implicit zone: DateTimeZone) =
    new DateTimeConverter(date.getTime, zone)
  
  implicit def sqlDateToDateTimeConverter(date: java.sql.Date)(implicit zone: DateTimeZone) =
    new DateTimeConverter(date.getTime, zone)
  
  implicit def sqlTimeToDateTimeConverter(time: java.sql.Time)(implicit zone: DateTimeZone) =
    new DateTimeConverter(time.getTime, zone)
  
  implicit def sqlTimestampToDateTimeConverter(timestamp: java.sql.Timestamp)(implicit zone: DateTimeZone) =
    new DateTime(timestamp.getTime, zone)
  
  
  class RichDateTime(date: DateTime) {
    def toLocalDateInZone(implicit zone: DateTimeZone): LocalDate = date.withZone(zone).toLocalDate
    def toLocalTimeInZone(implicit zone: DateTimeZone): LocalTime = date.withZone(zone).toLocalTime
    def toHttpHeaderString: String = HEADER_DATETIME_FORMAT.print(date)
    def toStandardTimeString: String = STANDARD_DATETIME_FORMAT.print(date)
    
    def isSameDay(otherDate: DateTime)(implicit zone: DateTimeZone): Boolean = {
      val z = date.withZone(zone)
      val z2 = otherDate.withZone(zone)
      z.getDayOfYear == z2.getDayOfYear && z.getYear == z2.getYear
    }
    
    def isSameDay(ld: LocalDate)(implicit zone: DateTimeZone): Boolean = ld == date.withZone(zone).toLocalDate
    
    lazy val format = HEADER_DATETIME_FORMAT.print(date)
  }
  
  implicit def dateTimeToRichDateTime(d: DateTime) = new RichDateTime(d)
  
  
  class RichTimeZone(zone: DateTimeZone) {
    def localDateFor(d: DateTime): LocalDate = d.withZone(zone).toLocalDate
    def localTimeFor(d: DateTime): LocalTime = d.withZone(zone).toLocalTime
  }
  implicit def dateTimeZoneToRichTimeZone(zone: DateTimeZone) = new RichTimeZone(zone)
  
  
  class RichLocalDate(ld: LocalDate) {
    def isSameDay(d: DateTime)(implicit zone: DateTimeZone) = ld == d.withZone(zone).toLocalDate
  }
  implicit def localDateToRichLocalDate(ld: LocalDate) = new RichLocalDate(ld)
}
