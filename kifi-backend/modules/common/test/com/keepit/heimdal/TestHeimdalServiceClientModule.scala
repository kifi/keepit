package com.keepit.heimdal

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier


case class TestHeimdalServiceClientModule() extends HeimdalServiceClientModule {

  def configure() {
    install(HeimdalQueueDevModule())
  }

  @Singleton
  @Provides
  def heimdalServiceClient(airbrakeNotifier: AirbrakeNotifier): HeimdalServiceClient = new FakeHeimdalServiceClientImpl(airbrakeNotifier)

}
