package com.keepit.common.healthcheck

import play.api._
import play.api.libs.concurrent.Akka
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Akka
import akka.util.duration._
import com.keepit.inject._
import org.joda.time.DateTime
import com.google.inject._
import akka.actor.Scheduler

@ImplementedBy(classOf[BabysitterImpl])
trait Babysitter {
  def watch[A](timeout: BabysitterTimeout)(block: => A)(implicit app: Application): A
}

case class BabysitterTimeout(warnTimeout: akka.util.FiniteDuration, errorTimeout: akka.util.FiniteDuration)

class BabysitterImpl extends Babysitter with Logging {
  def watch[A](timeout: BabysitterTimeout)(block: => A)(implicit app: Application): A = {
    val startTime = inject[DateTime]
    val e = new Exception("Babysitter error timeout")
    val babysitter = inject[Scheduler].scheduleOnce(timeout.errorTimeout) {
      log.error("BABYSITTER: Process taking way too long. %ss".format(timeout.errorTimeout.toMillis.toDouble / 1000.0))
      log.error(e.getStackTrace() mkString "\n  ")
      inject[HealthcheckPlugin].addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some("Babysitter error timeout")))
    }
    val result = try {
      block
    }
    finally {
      val endTime = inject[DateTime]
      val difference = endTime.getMillis - endTime.getMillis
      if(difference > timeout.warnTimeout.toMillis) {
        val e = new Exception("Babysitter error timeout")
        log.warn("BABYSITTER: Process taking too long. %ss".format((difference.toDouble / 1000.0)))
        log.warn(e.getStackTrace() mkString "\n  ")
      }
      babysitter.cancel
    }
    result
  }
}