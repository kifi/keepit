package com.keepit.graph

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.service.ServiceType
import play.api.Mode.Mode

trait GraphServiceClientModule extends ScalaModule

case class ProdGraphServiceClientModule() extends GraphServiceClientModule {

  def configure() {}

  @Provides @Singleton
  def graphServiceClient(httpClient: HttpClient, serviceDiscovery: ServiceDiscovery, airbrakeNotifier: AirbrakeNotifier, cacheProvider: GraphCacheProvider, mode: Mode): GraphServiceClient = {
    new GraphServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.GRAPH),
      httpClient,
      airbrakeNotifier,
      cacheProvider,
      mode: Mode
    )
  }
}

