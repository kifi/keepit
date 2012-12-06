package com.keepit.common.healthcheck

import com.keepit.inject.AppScoped
import com.tzavellas.sse.guice.ScalaModule

case class FakeHealthcheckModule() extends ScalaModule {
  def configure(): Unit = {
    bind[HealthcheckPlugin].to[FakeHealthcheck]
  }
}
