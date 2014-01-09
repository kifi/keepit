package com.keepit.common

import com.keepit.common.logging.Logging
import play.modules.statsd.api.Statsd

package object performance {

  case class Stopwatch(tag: String) extends Logging {
    var startTime = System.nanoTime()
    var elapsedTime: Long = 0

    def stop(): Long = {
      elapsedTime = System.nanoTime - startTime
      elapsedTime
    }

    override def toString = s"[$tag] elapsed milliseconds: ${(elapsedTime/1000000d)}"

    def logTime() {
      log.info(toString)
    }
  }

  def timing[A](tag: String)(f: => A): A = {
    val sw = new Stopwatch(tag)
    val res = f
    sw.stop()
    sw.logTime()
    res
  }

  def timeWithStatsd[A](tag: String, statsdTag: String)(f: => A): A = {
    val sw = new Stopwatch(tag)
    val res = f
    sw.stop()
    sw.logTime()
    Statsd.increment(statsdTag)
    Statsd.timing(statsdTag, sw.elapsedTime / 1000000)
    res
  }
}
