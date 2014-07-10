package com.keepit.graph

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType

case class TestGraphServiceClientModule() extends GraphServiceClientModule {

  def configure() {}

  @Provides @Singleton
  def graphServiceClient(httpClient: HttpClient, serviceDiscovery: ServiceDiscovery, airbrakeNotifier: AirbrakeNotifier): GraphServiceClient = {
    new FakeGraphServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.GRAPH),
      httpClient,
      airbrakeNotifier
    )
  }
}
