package com.keepit.heimdal

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule

trait HeimdalServiceClientModule extends ScalaModule

case class ProdHeimdalServiceClientModule() extends HeimdalServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def heimdalServiceClient (
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier): HeimdalServiceClient = {
    new HeimdalServiceClientImpl(
      airbrakeNotifier,
      client,
      serviceDiscovery.serviceCluster(ServiceType.HEIMDAL)
      )
  }

}


