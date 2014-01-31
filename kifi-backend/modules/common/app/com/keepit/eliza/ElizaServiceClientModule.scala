package com.keepit.eliza

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.{ServiceClient, ServiceType}
import play.api.Play._
import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}
import com.keepit.eliza.model.UserThreadStatsForUserIdCache

trait ElizaServiceClientModule extends ScalaModule

case class ProdElizaServiceClientModule() extends ElizaServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def elizaServiceClient (
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    serviceClientBinder: ScalaMultibinder[ServiceClient],
    airbrakeNotifier: AirbrakeNotifier,
    userThreadStatsForUserIdCache: UserThreadStatsForUserIdCache): ElizaServiceClient = {
    val eliza = new ElizaServiceClientImpl(
      airbrakeNotifier,
      client,
      serviceDiscovery.serviceCluster(ServiceType.ELIZA),
      userThreadStatsForUserIdCache
    )
    serviceClientBinder.addBinding().toInstance(eliza)
    eliza
  }
}

