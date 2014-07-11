package com.keepit.maven

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier

case class FakeMavenServiceClientModule() extends MavenServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def MavenServiceClient(airbrake: AirbrakeNotifier): MavenServiceClient = {
    new FakeMavenServiceClientImpl(airbrake)
  }

}
