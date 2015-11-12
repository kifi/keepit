package com.keepit.common.util

import org.joda.time.{ DateTime, Period }
import scala.collection.mutable
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }

trait Debouncing {
  protected val refractoryPeriod: Period
  protected val cooldownByKey: mutable.Map[String, DateTime] = mutable.Map.empty

  protected def debounce(key: String)(action: => Unit): Unit = {
    val now = currentDateTime
    cooldownByKey.get(key) match {
      case Some(threshold) if now.isBefore(threshold) =>
      case _ =>
        action
        cooldownByKey.put(key, now.plus(refractoryPeriod))
    }
  }
}
