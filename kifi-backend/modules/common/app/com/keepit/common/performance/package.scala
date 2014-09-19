package com.keepit.common

import com.keepit.common.logging.Logging
import play.api.Logger

package object performance {

  case class Stopwatch(tag: String) extends Logging {
    var startTime = System.nanoTime()
    var elapsedTime: Long = 0

    def stop(): Long = {
      elapsedTime = System.nanoTime - startTime
      elapsedTime
    }

    def resultString(res: String) = s"[$tag] result: $res; elapsed milliseconds: ${elapsedTime / 1000000d}"
    override def toString = s"[$tag] elapsed milliseconds: ${elapsedTime / 1000000d}"

    def logTime()(implicit logger: Logger = log) {
      logger.info(toString)
    }

    def logTimeWith(res: String)(implicit logger: Logger = log) {
      logger.info(resultString(res))
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
