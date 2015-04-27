package com.keepit.rover

import com.keepit.common.service.ServiceType
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.Mode.Mode

import scala.concurrent.ExecutionContext

trait RoverServiceClientModule extends ScalaModule

case class ProdRoverServiceClientModule() extends RoverServiceClientModule {

  def configure() {}

  @Provides @Singleton
  def roverServiceClient(httpClient: HttpClient, serviceDiscovery: ServiceDiscovery, airbrakeNotifier: AirbrakeNotifier, cacheProvider: RoverCacheProvider, executionContext: ExecutionContext): RoverServiceClient = {
    new RoverServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.ROVER),
      httpClient,
      airbrakeNotifier,
      cacheProvider,
      executionContext
    )
  }
}

