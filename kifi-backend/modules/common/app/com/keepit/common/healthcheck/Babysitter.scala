package com.keepit.common.healthcheck

import play.api._
import play.api.libs.concurrent.Akka
import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
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
  def watch[A](timeout: BabysitterTimeout)(block: => A): A
}

case class BabysitterTimeout(warnTimeout: FiniteDuration, errorTimeout: FiniteDuration)

class BabysitterImpl @Inject() (
    clock: Clock,
    airbrake: AirbrakeNotifier,
    scheduler: Scheduler) extends Babysitter with Logging {
  def watch[A](timeout: BabysitterTimeout)(block: => A): A = {
    val startTime = clock.now()
    val e = new Exception("Babysitter error timeout after %s millis".format(timeout.errorTimeout.toMillis))
    val pointer = new AtomicReference[ExternalId[AirbrakeError]]()
    val babysitter = scheduler.scheduleOnce(timeout.errorTimeout) {
      log.error(e.getStackTrace() mkString "\n  ")
      val error = airbrake.notify(e)
      pointer.set(error.id)
    }
    val result = try {
      block
    }
    finally {
      val endTime = clock.now()
      val difference = endTime.getMillis - endTime.getMillis
      val error = Option(pointer.get) map {he => "error %s".format(he)} getOrElse "No Error"
      if(difference > timeout.warnTimeout.toMillis) {
        val e = new Exception("Babysitter timeout [%s]. Process took %s millis, timeout was set to %s millis".
                                format(error, difference, timeout.warnTimeout.toMillis))
        log.warn(e.getStackTrace() mkString "\n  ")
      }
      babysitter.cancel
    }
    result
  }
}
