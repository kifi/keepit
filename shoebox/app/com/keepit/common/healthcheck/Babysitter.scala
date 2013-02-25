package com.keepit.common.healthcheck

import play.api._
import play.api.libs.concurrent.Akka
import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.keepit.inject._
import com.google.inject._
import play.api.libs.concurrent.Execution.Implicits._
import akka.actor.Scheduler
import org.joda.time.DateTime
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._

@ImplementedBy(classOf[BabysitterImpl])
trait Babysitter {
  def watch[A](timeout: BabysitterTimeout)(block: => A)(implicit app: Application): A
}

case class BabysitterTimeout(warnTimeout: FiniteDuration, errorTimeout: FiniteDuration)

class BabysitterImpl extends Babysitter with Logging {
  def watch[A](timeout: BabysitterTimeout)(block: => A)(implicit app: Application): A = {
    val startTime = inject[DateTime]
    val e = new Exception("Babysitter error timeout after %s millis".format(timeout.errorTimeout.toMillis))
    val pointer = new AtomicReference[ExternalId[HealthcheckError]]()
    val babysitter = inject[Scheduler].scheduleOnce(timeout.errorTimeout) {
      log.error(e.getStackTrace() mkString "\n  ")
      val error = inject[HealthcheckPlugin].addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(e.getMessage())))
      pointer.set(error.id)
    }
    val result = try {
      block
    }
    finally {
      val endTime = inject[DateTime]
      val difference = endTime.getMillis - endTime.getMillis
      val healthcheckError = Option(pointer.get) map {he => "HealthcheckError %s".format(he)} getOrElse "No Healthcheck Error"
      if(difference > timeout.warnTimeout.toMillis) {
        val e = new Exception("Babysitter timeout [%s]. Process took %s millis, timeout was set to %s millis".
                                format(healthcheckError, difference, timeout.warnTimeout.toMillis))
        log.warn(e.getStackTrace() mkString "\n  ")
      }
      babysitter.cancel
    }
    result
  }
}