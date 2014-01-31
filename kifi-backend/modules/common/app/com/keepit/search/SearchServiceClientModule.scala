package com.keepit.search

import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}
import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.{ServiceClient, ServiceType}
import play.api.Play._

trait SearchServiceClientModule extends ScalaModule

case class ProdSearchServiceClientModule() extends SearchServiceClientModule {

  def configure {}

  @Singleton
  @Provides
  def searchServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    serviceClientBinder: ScalaMultibinder[ServiceClient],
    airbrakeNotifier: AirbrakeNotifier): SearchServiceClient = {
    val search = new SearchServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.SEARCH),
      current.configuration.getInt("service.search.port").get,
      client,
      airbrakeNotifier
    )
    serviceClientBinder.addBinding().toInstance(search)
    search
  }

}
