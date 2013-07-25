package com.keepit.shoebox

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule

trait ShoeboxServiceClientModule extends ScalaModule

case class ProdShoeboxServiceClientModule() extends ShoeboxServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def shoeboxServiceClient (
    client: HttpClient,
    cacheProvider: ShoeboxCacheProvider,
    serviceDiscovery: ServiceDiscovery,
    healthcheck: HealthcheckPlugin): ShoeboxServiceClient = {
    new ShoeboxServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.SHOEBOX),
      current.configuration.getInt("service.shoebox.port").get,
      client, cacheProvider, healthcheck)
  }

}
