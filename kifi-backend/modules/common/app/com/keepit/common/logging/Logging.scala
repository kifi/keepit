package com.keepit.common.logging

import play.api.Logger

case class LogPrefix(val s:String) extends AnyVal {
  override def toString = s
}

object LogPrefix {
  val EMPTY = LogPrefix("")
}

trait Logging {
  implicit lazy val log = Logger(getClass)
}

object Logging {
  implicit class LoggerWithPrefix(val l:Logger) extends AnyVal {
    def traceP(s:String)(implicit prefix:LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) l.trace(s) else l.trace(s"[$prefix] $s")
    def debugP(s:String)(implicit prefix:LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) l.debug(s) else l.debug(s"[$prefix] $s")
    def infoP(s:String)(implicit prefix:LogPrefix = LogPrefix.EMPTY)  = if (prefix == LogPrefix.EMPTY) l.info(s) else l.info(s"[$prefix] $s")
    def warnP(s:String)(implicit prefix:LogPrefix = LogPrefix.EMPTY)  = if (prefix == LogPrefix.EMPTY) l.warn(s) else l.warn(s"[$prefix] $s")
    def errorP(s:String)(implicit prefix:LogPrefix = LogPrefix.EMPTY) = if (prefix == LogPrefix.EMPTY) l.error(s) else l.error(s"[$prefix] $s")
  }
}