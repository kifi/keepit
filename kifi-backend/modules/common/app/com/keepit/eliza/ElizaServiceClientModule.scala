package com.keepit.eliza

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule

trait ElizaServiceClientModule extends ScalaModule

case class ProdElizaServiceClientModule() extends ElizaServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def elizaServiceClient (
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    healthcheck: HealthcheckPlugin): ElizaServiceClient = {
    new ElizaServiceClientImpl(
      healthcheck,
      client,
      serviceDiscovery.serviceCluster(ServiceType.ELIZA)
      )
  }

}


case class TestElizaServiceClientModule() extends ElizaServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def elizaServiceClient(healthcheck: HealthcheckPlugin): ElizaServiceClient = {
    new FakeElizaServiceClientImpl(healthcheck)
  }

}
