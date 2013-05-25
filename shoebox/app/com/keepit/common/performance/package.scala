package com.keepit.common

import com.keepit.common.logging.Logging

package object performance {
  class Stopwatch(tag: String) extends Logging {
    var startTime = System.nanoTime
    var elapsedTime: Long = 0

    def stop(): Long = { elapsedTime = System.nanoTime - startTime; elapsedTime }

    override def toString = s"[$tag] elapsed milliseconds: ${(elapsedTime/1000000d)}"

    def logTime() = log.info(toString)
  }

  def time[A](tag: String)(f: => A): A = {
    val sw = new Stopwatch(tag)
    val res = f
    sw.stop
    sw.logTime()
    res
  }
}
