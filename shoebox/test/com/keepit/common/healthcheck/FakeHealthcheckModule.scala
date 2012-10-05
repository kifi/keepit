package com.keepit.common.healthcheck

import com.keepit.inject._
import com.tzavellas.sse.guice.ScalaModule

case class FakeHealthcheckModule() extends ScalaModule {
  def configure(): Unit = {
    bind[Healthcheck].to[FakeHealthcheck].in[AppScoped]
  }
}
