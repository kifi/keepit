package com.keepit.common.akka

import akka.actor.Actor
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}

abstract class FortyTwoActor(healthcheck: HealthcheckPlugin) extends Actor {
  override def preRestart(reason: Throwable, message: Option[Any]) {
    healthcheck.addError(
      HealthcheckError(error = Some(reason),
        callType = Healthcheck.INTERNAL,
        errorMessage = Some("Actor %s threw an uncaught exception. Message: %s".format(this.getClass.getSimpleName, message))
      )
    )
    postStop()
  }
}
