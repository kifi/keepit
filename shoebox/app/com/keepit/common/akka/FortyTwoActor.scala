package com.keepit.common.akka

import akka.actor.Actor
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}

trait AlertingActor extends Actor {
  def alert(reason: Throwable, message: Option[Any])
  def error(reason: Throwable, message: Option[Any]) =
    HealthcheckError(error = Some(reason),
      callType = Healthcheck.INTERNAL,
      errorMessage = Some("Actor %s threw an uncaught exception. Message: %s".format(this.getClass.getSimpleName, message))
    )

  override def preRestart(reason: Throwable, message: Option[Any]) {
    alert(reason, message)
    postStop()
  }
}

abstract class FortyTwoActor(healthcheck: HealthcheckPlugin) extends AlertingActor {
  def alert(reason: Throwable, message: Option[Any]) =
    healthcheck.addError(error(reason, message))
}
