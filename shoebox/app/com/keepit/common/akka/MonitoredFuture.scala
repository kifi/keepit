package com.keepit.common.akka

import com.google.inject.Inject
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import scala.concurrent.Future

class MonitoredFuture @Inject() (healthcheckPlugin: HealthcheckPlugin) {

    def recover[T](future: Future[T], pf : scala.PartialFunction[Throwable,T])(implicit executor : scala.concurrent.ExecutionContext) = {
      future.recover {
        case ex: Throwable =>
          healthcheckPlugin.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
          pf(ex)
      }
    }
}
