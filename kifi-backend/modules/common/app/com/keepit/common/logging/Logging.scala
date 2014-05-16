package com.keepit.common.logging

import play.modules.statsd.api.{StatsdClient, Statsd}
import play.api.Logger

case class LogPrefix(prefix: String) extends AnyVal {
  override def toString = prefix
}

object LogPrefix {
  val EMPTY = LogPrefix("")
}

trait Logging {
  implicit lazy val log = Logger(getClass)
  implicit lazy val statsd = new StatsdWrapper(Logger(getClass.getCanonicalName))
}

/**
 * Signatures and default values copied from
 * https://github.com/typesafehub/play-plugins/blob/master/statsd/src/main/scala/play/modules/statsd/api/StatsdClient.scala
 */
class StatsdWrapper extends StatsdClient {

  /**
   * Increment a given stat key. Optionally give it a value and sampling rate.
   *
   * @param key The stat key to be incremented.
   * @param value The amount by which to increment the stat. Defaults to 1.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   */
  def increment(key: String, value: Long = 1, samplingRate: Double = 1.0): Unit = {
    increment(key, value, samplingRate)

  }

  /**
   * Timing data for given stat key. Optionally give it a sampling rate.
   *
   * @param key The stat key to be timed.
   * @param millis The number of milliseconds the operation took.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   */
  def timing(key: String, millis: Long, samplingRate: Double = 1.0) {
    safely { maybeSend(statFor(key, millis, TimingSuffix, samplingRate), samplingRate) }
  }

  /**
   * Time a given operation and send the resulting stat.
   *
   * @param key The stat key to be timed.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   * @param timed An arbitrary block of code to be timed.
   * @return The result of the timed operation.
   */
  def time[T](key: String, samplingRate: Double = 1.0)(timed: => T): T = {
    val start = now()
    val result = timed
    val finish = now()
    timing(key, finish - start, samplingRate)
    result
  }

  /**
   * Record the given value.
   *
   * @param key The stat key to update.
   * @param value The value to record for the stat.
   */
  def gauge(key: String, value: Long) {
    safely { maybeSend(statFor(key, value, GaugeSuffix, 1.0), 1.0) }
  }

  /**
   * Record the given value.
   *
   * @param key The stat key to update.
   * @param value The value to record for the stat.
   */
  def gauge(key: String, value: Long, delta: Boolean) {
    if (!delta) {
      safely { maybeSend(statFor(key, value, GaugeSuffix, 1.0), 1.0) }
    } else {
      if (value >= 0) {
        safely { maybeSend(statFor(key, "+".concat(value.toString), GaugeSuffix, 1.0), 1.0) }
      } else {
        safely { maybeSend(statFor(key, value.toString, GaugeSuffix, 1.0), 1.0) }
      }
    }
  }
}


object Logging {
  implicit class LoggerWithPrefix(val log: Logger) extends AnyVal {
    def traceP(message: => String)(implicit prefix:LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) log.trace(message) else log.trace(s"[$prefix] $message")
    def debugP(message: => String)(implicit prefix:LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) log.debug(message) else log.debug(s"[$prefix] $message")
    def infoP(message: => String)(implicit prefix:LogPrefix = LogPrefix.EMPTY)  = if (prefix == LogPrefix.EMPTY) log.info(message) else log.info(s"[$prefix] $message")
    def warnP(message: => String)(implicit prefix:LogPrefix = LogPrefix.EMPTY)  = if (prefix == LogPrefix.EMPTY) log.warn(message) else log.warn(s"[$prefix] $message")
    def errorP(message: => String)(implicit prefix:LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) log.error(message) else log.error(s"[$prefix] $message")
  }
}
