package com.keepit.common.akka

import akka.actor.Actor
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}

trait AlertingActor extends Actor {
  def alert(reason: Throwable, message: Option[Any])
  def error(reason: Throwable, message: Option[Any]) =
    AirbrakeError(exception = reason,
      message = Some(s"Actor ${this.getClass.getSimpleName} threw an uncaught exception. Message: ${message.getOrElse("")}")
    )

  override def preRestart(reason: Throwable, message: Option[Any]) {
    alert(reason, message)
    postStop()
  }
}

abstract class FortyTwoActor(airbrake: AirbrakeNotifier) extends AlertingActor {
  def alert(reason: Throwable, message: Option[Any]) = airbrake.notify(error(reason, message))
}

class UnsupportedActorMessage(any: Any) extends IllegalStateException(if (any != null) any.toString else "Message is NULL")
