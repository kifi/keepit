package com.keepit.common.akka

import akka.actor.Actor
import com.keepit.common.strings._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }

trait AlertingActor extends Actor with Logging {
  def alert(reason: Throwable, message: Option[Any])
  def error(reason: Throwable, message: Option[Any]) =
    AirbrakeError(exception = reason,
      message = Some(s"On Actor ${this.getClass.getSimpleName}: ${message.getOrElse("").toString.abbreviate(50)}")
    )

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(s"Actor ${this.getClass.getSimpleName} is preRestarting ...")
    alert(reason, message)
    super.preRestart(reason, message)
  }
}

abstract class FortyTwoActor(airbrake: AirbrakeNotifier) extends AlertingActor with Logging {
  def alert(reason: Throwable, message: Option[Any]) = {
    log.error(message.getOrElse("No Message").toString, reason)
    airbrake.notify(error(reason, message))
  }
}

class UnsupportedActorMessage(any: Any) extends IllegalStateException(if (any != null) any.toString else "Message is NULL")
