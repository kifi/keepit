package com.keepit.curator

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier

case class FakeCuratorServiceClientModule() extends CuratorServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def CuratorServiceClient(airbrake: AirbrakeNotifier): CuratorServiceClient = {
    new FakeCuratorServiceClientImpl(airbrake)
  }

}
