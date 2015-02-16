package com.keepit.common

import com.keepit.common.logging.Logging
import play.api.Logger

package object performance {

  case class Stopwatch(tag: String) extends Logging {
    var startTime = System.nanoTime()
    var lastLap: Long = startTime
    var elapsedTime: Long = 0

    def stop(): Long = {
      elapsedTime = System.nanoTime - startTime
      elapsedTime
    }

    override def toString = s"[$tag] elapsed milliseconds: ${elapsedTime / 1000000d}"

    private def recordLap() = {
      val now = System.nanoTime
      val sinceStart = now - startTime
      val lapTime = now - lastLap
      lastLap = now
      (sinceStart, lapTime)
    }

    def logTime()(implicit logger: Logger = log): Long = {
      val (sinceStart, lapTime) = recordLap()
      logger.info(s"lap:${lapTime / 1000000d}ms; sinceStart:${sinceStart / 1000000d}ms; $tag")
      lapTime
    }

    def logTimeWith(res: String)(implicit logger: Logger = log) {
      val (sinceStart, lapTime) = recordLap()
      logger.info(s"lap:${lapTime / 1000000d}ms; sinceStart:${sinceStart / 1000000d}ms; $tag - $res")
    }
  }

  def timing[A](tag: String)(f: => A)(implicit logger: Logger): A = {
    val sw = new Stopwatch(tag)
    val res = f
    sw.stop()
    sw.logTime()
    res
  }

  def timing[A](tag: String, threshold: Long, conditionalBlock: Option[Long => Unit] = None)(f: => A)(implicit logger: Logger): A = {
    val sw = new Stopwatch(tag)
    val res = f
    val elapsed = sw.stop()
    val elapsedMili = elapsed / 1000000
    if (elapsedMili > threshold) {
      conditionalBlock match {
        case Some(block) => block(elapsedMili)
        case None => sw.logTime()
      }
    }
    res
  }

  def timingWithResult[A](tag: String, r: (A => String) = { a: A => a.toString })(f: => A)(implicit logger: Logger): A = {
    val sw = new Stopwatch(tag)
    val res = f
    val resString = r(res)
    sw.stop()
    sw.logTimeWith(resString)
    res
  }
}
