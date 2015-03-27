package com.keepit.rover.document.utils

import java.util.Locale

import com.keepit.common.logging.Logging
import com.keepit.common.time._
import org.joda.time.DateTime
import org.joda.time.format.{ DateTimeFormat, DateTimeParser, ISODateTimeFormat, DateTimeFormatterBuilder }

object DateTimeMetadataParser extends Logging {

  private[this] val MULTI_FORMAT = {
    val simpleFormat = // yyyy-MM-dd HH:mm:ss.SSS Z
      new DateTimeFormatterBuilder()
        .append(ISODateTimeFormat.dateElementParser)
        .appendLiteral(' ')
        .append(ISODateTimeFormat.timeElementParser.getParser)
        .appendOptional(new DateTimeFormatterBuilder().appendTimeZoneOffset("Z", true, 2, 4).toFormatter.getParser).toFormatter

    new DateTimeFormatterBuilder().append(
      ISODateTimeFormat.dateTime.getPrinter,
      Array[DateTimeParser](
        ISODateTimeFormat.dateTimeParser.getParser,
        simpleFormat.getParser,
        DateTimeFormat.forPattern("yyyyMMddHHmmss").getParser,
        ISODateTimeFormat.basicDate.getParser)
    ).toFormatter.withLocale(Locale.ENGLISH).withZone(DEFAULT_DATE_TIME_ZONE).withOffsetParsed()
  }

  def parse(dateTimeString: String): Option[DateTime] = {
    try {
      Some(DateTime.parse(dateTimeString, MULTI_FORMAT))
    } catch {
      case e: Throwable =>
        log.error(s"unable to parse datetime string: [$dateTimeString]")
        None
    }
  }
}