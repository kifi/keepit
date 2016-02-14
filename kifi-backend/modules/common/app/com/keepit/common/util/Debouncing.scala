package com.keepit.common.util

import java.util.concurrent.TimeUnit

import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, currentDateTime }
import org.jboss.netty.util.{ HashedWheelTimer, Timeout, TimerTask }
import org.joda.time.DateTime

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.util.Try

object Debouncing {
  class Dropper[T] {
    private val onCooldownUntil: mutable.Map[String, DateTime] = mutable.Map.empty
    def debounce(key: String, cooldown: Duration)(fn: => T): Option[T] = {
      val now = currentDateTime
      onCooldownUntil.get(key) match {
        case Some(threshold) if now.isBefore(threshold) => None
        case _ =>
          onCooldownUntil.put(key, now.plusMillis(cooldown.toMillis.toInt))
          Some(fn)
      }
    }
  }

  class Buffer[T] {
    private val bufMap: mutable.Map[String, ListBuffer[T]] = mutable.Map.empty
    private val timer = new HashedWheelTimer(1, TimeUnit.MILLISECONDS)
    private val lock = new Object
    def debounce(key: String, cooldown: Duration)(item: T)(action: List[T] => Unit): Unit = Try(lock.synchronized {
      if (!bufMap.isDefinedAt(key)) {
        bufMap.put(key, ListBuffer.empty)
        timer.newTimeout(new TimerTask {
          override def run(timeout: Timeout): Unit = lock.synchronized { bufMap.remove(key).foreach(buf => action(buf.toList)) }
        }, cooldown.toMillis, TimeUnit.MILLISECONDS)
      }
      bufMap(key).prepend(item)
    })
  }
}
