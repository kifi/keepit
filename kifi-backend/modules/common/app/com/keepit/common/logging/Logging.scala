package com.keepit.common.logging

import play.api.Logger

case class LogPrefix(prefix: String) extends AnyVal {
  override def toString = prefix
}

object LogPrefix {
  val EMPTY = LogPrefix("")
}

trait Logging {
  implicit lazy val log = Logger(getClass)
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