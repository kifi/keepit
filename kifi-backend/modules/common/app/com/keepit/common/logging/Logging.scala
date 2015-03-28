package com.keepit.common.logging

import com.keepit.macros.Location
import play.modules.statsd.api.Statsd
import play.api.Logger
import java.util.Random

case class LogPrefix(prefix: String) extends AnyVal {
  override def toString = prefix
}

object LogPrefix {
  val EMPTY = LogPrefix("")
}

trait Logging {
  val ALWAYS = 1.0d
  val ONE_IN_TEN = 0.1d
  val ONE_IN_HUNDRED = 0.01d
  val ONE_IN_THOUSAND = 0.001d
  val ONE_IN_TEN_THOUSAND = 0.0001d
  implicit lazy val log = Logger(getClass)
  implicit lazy val statsd = new LoggingStatsdClient(Logger(s"statsd.${getClass.getCanonicalName}"))

  implicit def captureLocation: Location = macro Location.locationMacro
}

class Timer(startTime: Long = System.currentTimeMillis()) {
  def timeSinceStarted: Long = System.currentTimeMillis() - startTime
}

/**
 * Signatures and default values copied from
 * https://github.com/typesafehub/play-plugins/blob/master/statsd/src/main/scala/play/modules/statsd/api/StatsdClient.scala
 */
class LoggingStatsdClient(log: Logger) {
  private[this] val random = new Random()
  private def nextFloat(): Float = random.nextFloat()

  /**
   * Increment a given stat key. Optionally give it a value and sampling rate.
   *
   * @param key The stat key to be incremented.
   * @param value The amount by which to increment the stat. Defaults to 1.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   */
  def increment(key: String, value: Long, samplingRate: Double): Unit = {
    maybeLog(s"[increment] key: $key, value: $value, samplingRate: $samplingRate", samplingRate)
    Statsd.increment(key, value, samplingRate)
  }

  def incrementOne(key: String, samplingRate: Double): Unit =
    increment(key, 1, samplingRate)

  /**
   * Timing data for given stat key. Optionally give it a sampling rate.
   *
   * @param key The stat key to be timed.
   * @param millis The number of milliseconds the operation took.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   */
  def timing(key: String, millis: Long, samplingRate: Double): Unit = {
    maybeLog(s"[timing] key: $key, millis: $millis, samplingRate: $samplingRate", samplingRate)
    Statsd.timing(key, millis, samplingRate)
  }

  def timing(key: String, timer: Timer, samplingRate: Double): Unit = timing(key, timer.timeSinceStarted, samplingRate)

  /**
   * Time a given operation and send the resulting stat.
   *
   * @param key The stat key to be timed.
   * @param samplingRate The probability for which to increment. Defaults to 1.
   * @param timed An arbitrary block of code to be timed.
   * @return The result of the timed operation.
   */
  def time[T](key: String, samplingRate: Double)(timed: Timer => T): T = {
    maybeLog(s"[time] key: $key, samplingRate: $samplingRate", samplingRate)
    val timer = new Timer()
    val result = timed(timer)
    val timeSinceStarted = timer.timeSinceStarted
    timing(key, timeSinceStarted, samplingRate)
    result
  }

  /**
   * Record the given value.
   *
   * @param key The stat key to update.
   * @param value The value to record for the stat.
   */
  def gauge(key: String, value: Long): Unit = {
    maybeLog(s"[gauge] key: $key, value: $value")
    Statsd.gauge(key, value)
  }

  /**
   * Record the given value.
   *
   * @param key The stat key to update.
   * @param value The value to record for the stat.
   */
  def gauge(key: String, value: Long, delta: Boolean): Unit = {
    maybeLog(s"[gauge] key: $key, value: $value, delta: $delta")
    Statsd.gauge(key, value, delta)
  }

  /**
   * Probabilistically calls the {@code log} function. Use a random number call send
   * function {@code (samplingRate * 10)%} of the time unless samplingRate == 1 then we'll always log.
   * Means that when sampling, we log at the rate of 1/10 of what we send to the server.
   * Note: the sampling rate of spitting to the log is the same as sending to statsd but the probability
   * is calculated separately for each in order to to be intrusive on the current implementation of the client.
   */
  private def maybeLog(msg: => String, samplingRate: Double = 1.0) {
    if (samplingRate >= 1.0 || nextFloat() < (samplingRate / 10.0d)) {
      log.info(msg)
    }
  }
}

class NamedStatsdTimer(name: String) extends Logging {
  private val t0 = System.currentTimeMillis
  def stopAndReport(scaling: Double = 1.0): Unit = {
    val elapsed = (System.currentTimeMillis - t0) / scaling
    Statsd.timing(name, elapsed.toLong, 1.0)
  }
}

object Logging {
  implicit class LoggerWithPrefix(val log: Logger) extends AnyVal {
    def traceP(message: => String)(implicit prefix: LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) log.trace(message) else log.trace(s"[$prefix] $message")
    def debugP(message: => String)(implicit prefix: LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) log.debug(message) else log.debug(s"[$prefix] $message")
    def infoP(message: => String)(implicit prefix: LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) log.info(message) else log.info(s"[$prefix] $message")
    def warnP(message: => String)(implicit prefix: LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) log.warn(message) else log.warn(s"[$prefix] $message")
    def errorP(message: => String)(implicit prefix: LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) log.error(message) else log.error(s"[$prefix] $message")
  }
}
