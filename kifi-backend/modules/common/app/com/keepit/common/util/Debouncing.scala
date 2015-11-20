package com.keepit.common.util

import org.joda.time.{ DateTime, Period }
import scala.collection.mutable
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }

trait Debouncing {
  protected val onCooldownUntil: mutable.Map[String, DateTime] = mutable.Map.empty

  protected def debounce(key: String, cooldown: Period)(action: => Unit): Unit = {
    val now = currentDateTime
    onCooldownUntil.get(key) match {
      case Some(threshold) if now.isBefore(threshold) =>
      case _ =>
        action
        onCooldownUntil.put(key, now.plus(cooldown))
    }
  }
}
