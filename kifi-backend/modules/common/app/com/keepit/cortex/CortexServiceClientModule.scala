package com.keepit.cortex

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._

trait CortexServiceClientModule extends ScalaModule

case class ProdCortexServiceClientModule() extends CortexServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def cortexServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier): CortexServiceClient = {
    new CortexServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.CORTEX),
      client,
      airbrakeNotifier
    )
  }
}
