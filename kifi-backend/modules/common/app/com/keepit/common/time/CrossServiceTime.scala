package com.keepit.common.time

import com.keepit.common.time
import org.joda.time.DateTime
import play.api.libs.json.{ Format, JsNumber, Reads, Writes }

final case class CrossServiceTime(time: DateTime) extends AnyVal
object CrossServiceTime {
  implicit val format: Format[CrossServiceTime] = Format(
    Reads { js => js.validate[Long].map(millis => CrossServiceTime(new DateTime(millis, time.DEFAULT_DATE_TIME_ZONE))) },
    Writes { time => JsNumber(time.time.getMillis) }
  )
}

