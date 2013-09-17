package com.keepit.heimdal

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.HealthcheckPlugin
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
    healthcheck: HealthcheckPlugin): HeimdalServiceClient = {
    new HeimdalServiceClientImpl(
      healthcheck,
      client,
      serviceDiscovery.serviceCluster(ServiceType.HEIMDAL)
      )
  }

}


case class TestHeimdalServiceClientModule() extends HeimdalServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def heimdalServiceClient(healthcheck: HealthcheckPlugin): HeimdalServiceClient = {
    new FakeHeimdalServiceClientImpl(healthcheck)
  }

}
