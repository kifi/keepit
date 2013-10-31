package com.keepit.heimdal

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule


case class TestHeimdalServiceClientModule() extends HeimdalServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def heimdalServiceClient(airbrakeNotifier: AirbrakeNotifier): HeimdalServiceClient = {
    new FakeHeimdalServiceClientImpl(airbrakeNotifier)
  }

}
