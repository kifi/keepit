package com.keepit.common.logging

import play.modules.statsd.api.{StatsdClientCake, StatsdClient, Statsd}
import play.api.Logger

case class LogPrefix(prefix: String) extends AnyVal {
  override def toString = prefix
}

object LogPrefix {
  val EMPTY = LogPrefix("")
}

trait Logging {
  implicit lazy val log = Logger(getClass)
  implicit lazy val statsd = new LoggingStatsdClient(Logger(s"statsd.${getClass.getCanonicalName}"))
}

/**
 * Signatures and default values copied from
 * https://github.com/typesafehub/play-plugins/blob/master/statsd/src/main/scala/play/modules/statsd/api/StatsdClient.scala
 */
class LoggingStatsdClient(log: Logger) extends StatsdClient with StatsdClientCake {
  //Stupid Cake pattern ditching!
  protected val statPrefix: String = ""
  protected val send: Function1[String, Unit] = {foo => }
  protected def now(): Long = -1
  protected def nextFloat(): Float = -1.0f

  /**
   * Increment a given stat key. Optionally give it a value and sampling rate.
   *
   * @param key The stat key to be incremented.
   * @param value The amount by which to increment the stat. Defaults to 1.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   */
  override def increment(key: String, value: Long = 1, samplingRate: Double = 1.0): Unit = {
    log.info(s"[increment] key: $key, value: $value, samplingRate: $samplingRate")
    Statsd.increment(key, value, samplingRate)
  }

  /**
   * Timing data for given stat key. Optionally give it a sampling rate.
   *
   * @param key The stat key to be timed.
   * @param millis The number of milliseconds the operation took.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   */
  override def timing(key: String, millis: Long, samplingRate: Double = 1.0): Unit = {
    log.info(s"[timing] key: $key, millis: $millis, samplingRate: $samplingRate")
    Statsd.timing(key, millis, samplingRate)
  }

  /**
   * Time a given operation and send the resulting stat.
   *
   * @param key The stat key to be timed.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   * @param timed An arbitrary block of code to be timed.
   * @return The result of the timed operation.
   */
  override def time[T](key: String, samplingRate: Double = 1.0)(timed: => T): T = {
    log.info(s"[time] key: $key, samplingRate: $samplingRate")
    Statsd.time(key, samplingRate)(timed)
  }

  /**
   * Record the given value.
   *
   * @param key The stat key to update.
   * @param value The value to record for the stat.
   */
  override def gauge(key: String, value: Long): Unit = {
    log.info(s"[gauge] key: $key, value: $value")
    Statsd.gauge(key, value)
  }

  /**
   * Record the given value.
   *
   * @param key The stat key to update.
   * @param value The value to record for the stat.
   */
  override def gauge(key: String, value: Long, delta: Boolean): Unit = {
    log.info(s"[gauge] key: $key, value: $value, delta: $delta")
    Statsd.gauge(key, value, delta)
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
