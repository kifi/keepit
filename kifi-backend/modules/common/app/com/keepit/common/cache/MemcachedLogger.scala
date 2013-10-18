package com.keepit.common.cache

import net.spy.memcached.compat.log.AbstractLogger
import net.spy.memcached.compat.log.Level
import play.api.Logger

class MemcachedSlf4JLogger(name: String) extends AbstractLogger(name) {

  val logger = Logger("memcached")

  def isDebugEnabled = logger.isDebugEnabled

  def isInfoEnabled = logger.isInfoEnabled

  def log(level: Level, msg: AnyRef, throwable: Throwable) {
    val message = msg.toString
    level match {
      case Level.DEBUG => logger.debug(message, throwable)
      case Level.INFO => logger.info(message, throwable)
      case Level.WARN => logger.warn(message, throwable)
      case Level.ERROR => logger.error(message, throwable)
      case Level.FATAL => logger.error("[FATAL] " + message, throwable)
    }
  }
}
