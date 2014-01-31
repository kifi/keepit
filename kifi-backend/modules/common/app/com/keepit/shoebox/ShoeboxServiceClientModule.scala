package com.keepit.shoebox

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.{ServiceClient, ServiceType}
import play.api.Play._
import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}

trait ShoeboxServiceClientModule extends ScalaModule

case class ProdShoeboxServiceClientModule() extends ShoeboxServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def shoeboxServiceClient (
    client: HttpClient,
    cacheProvider: ShoeboxCacheProvider,
    serviceDiscovery: ServiceDiscovery,
    serviceClientBinder: ScalaMultibinder[ServiceClient],
    airbrakeNotifier: AirbrakeNotifier): ShoeboxServiceClient = {
    val shoebox = new ShoeboxServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.SHOEBOX),
      current.configuration.getInt("service.shoebox.port").get,
      client, airbrakeNotifier, cacheProvider
    )
    serviceClientBinder.addBinding().toInstance(shoebox)
    shoebox
  }

}
