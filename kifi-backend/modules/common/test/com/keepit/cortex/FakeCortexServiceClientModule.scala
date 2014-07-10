package com.keepit.cortex

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier

case class FakeCortexServiceClientModule() extends CortexServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def cortexServiceClient(airbrake: AirbrakeNotifier): CortexServiceClient = {
    new FakeCortexServiceClientImpl(airbrake)
  }

}
