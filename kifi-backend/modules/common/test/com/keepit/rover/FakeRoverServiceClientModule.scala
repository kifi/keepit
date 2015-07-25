package com.keepit.rover

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, AirbrakeNotifier }
import com.keepit.common.net.HttpClient
import com.keepit.common.time.Clock
import com.keepit.common.zookeeper.{ ServiceCluster, ServiceDiscovery }
import com.keepit.common.service.ServiceType

case class FakeRoverServiceClientModule() extends RoverServiceClientModule {

  def configure() {}

  @Provides @Singleton
  def roverServiceClient(httpClient: HttpClient, serviceDiscovery: ServiceDiscovery, airbrakeNotifier: AirbrakeNotifier, clock: Clock): RoverServiceClient = {
    new FakeRoverServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.ROVER),
      httpClient,
      airbrakeNotifier,
      clock
    )
  }
}

case class FakeRoverServiceModule() extends RoverServiceClientModule {
  override def configure(): Unit = {
    install(FakeAirbrakeModule())
  }

  @Singleton
  @Provides
  def roverServiceClient(serviceCluster: ServiceCluster, httpClient: HttpClient, airbrakeNotifier: AirbrakeNotifier, clock: Clock): RoverServiceClient =
    fakeRoverServiceClient(serviceCluster, httpClient, airbrakeNotifier, clock)

  @Singleton
  @Provides
  def fakeRoverServiceClient(serviceCluster: ServiceCluster, httpClient: HttpClient, airbrakeNotifier: AirbrakeNotifier, clock: Clock): FakeRoverServiceClientImpl =
    new FakeRoverServiceClientImpl(serviceCluster, httpClient, airbrakeNotifier, clock)

}
