package com.keepit.common.akka

import akka.actor.Actor
import com.keepit.inject._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import play.api.Play.current

trait FortyTwoActor extends Actor {
  override def preRestart(reason: Throwable, message: Option[Any]) {
    inject[HealthcheckPlugin].addError(
      HealthcheckError(error = Some(reason),
        callType = Healthcheck.INTERNAL,
        errorMessage = Some("Actor %s threw an uncaught exception. Message: %s".format(this.getClass.getSimpleName, message))
      )
    )
    postStop()
  }
}
