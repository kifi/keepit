package com.keepit.abook

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule

trait ABookServiceClientModule extends ScalaModule

case class ProdABookServiceClientModule() extends ABookServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def ABookServiceClient(client: HttpClient,
                         serviceDiscovery: ServiceDiscovery,
                         healthcheck: HealthcheckPlugin): ABookServiceClient = {
    new ABookServiceClientImpl(
      healthcheck,
      client,
      serviceDiscovery.serviceCluster(ServiceType.ABOOK)
    )
  }

}


case class TestABookServiceClientModule() extends ABookServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def ABookServiceClient(healthcheck: HealthcheckPlugin): ABookServiceClient = {
    new FakeABookServiceClientImpl(healthcheck)
  }

}
