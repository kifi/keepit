package com.keepit.graph

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, AirbrakeNotifier }
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.{ ServiceCluster, ServiceDiscovery }
import com.keepit.common.service.ServiceType

case class FakeGraphServiceModule() extends GraphServiceClientModule {
  override def configure(): Unit = {
    install(FakeAirbrakeModule())
  }

  @Singleton
  @Provides
  def graphServiceClient(client: FakeGraphServiceClientImpl): GraphServiceClient = client

  @Singleton
  @Provides
  def fakeGraphServiceClient(serviceCluster: ServiceCluster, httpClient: HttpClient, airbrakeNotifier: AirbrakeNotifier): FakeGraphServiceClientImpl =
    new FakeGraphServiceClientImpl(serviceCluster, httpClient, airbrakeNotifier)

}
