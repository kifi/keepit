package com.keepit.common.util

import org.joda.time.{ DateTime, Period }
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }

import scala.collection.mutable.ListBuffer
import scala.util.Try

object Debouncing {
  class Dropper[T] {
    private val onCooldownUntil: mutable.Map[String, DateTime] = mutable.Map.empty
    def debounce(key: String, cooldown: Period)(fn: => T): Option[T] = {
      val now = currentDateTime
      onCooldownUntil.get(key) match {
        case Some(threshold) if now.isBefore(threshold) => None
        case _ =>
          onCooldownUntil.put(key, now.plus(cooldown))
          Some(fn)
      }
    }
  }

  class Buffer[T] {
    private val onCooldownUntil: mutable.Map[String, DateTime] = mutable.Map.empty
    private val bufferMap: mutable.Map[String, ListBuffer[T]] = mutable.Map.empty
    private val lock = new Object

    def debounce(key: String, cooldown: Period)(item: T)(action: List[T] => Unit): Unit = lock.synchronized {
      Try {
        val now = currentDateTime
        if (!bufferMap.isDefinedAt(key)) bufferMap.put(key, ListBuffer.empty)

        val buffer = bufferMap(key)
        buffer.prepend(item)
        onCooldownUntil.get(key) match {
          case Some(threshold) if now isBefore threshold =>
          case _ =>
            onCooldownUntil.put(key, now plus cooldown)
            val input = buffer.toList
            buffer.clear()
            action(input)
        }
      }
    }
  }
}
