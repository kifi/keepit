package com.keepit.eliza

import scala.concurrent.ExecutionContext
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.eliza.model.UserThreadStatsForUserIdCache

trait ElizaServiceClientModule extends ScalaModule

case class ProdElizaServiceClientModule() extends ElizaServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def elizaServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier,
    defaultContext: ExecutionContext,
    userThreadStatsForUserIdCache: UserThreadStatsForUserIdCache): ElizaServiceClient = {
    new ElizaServiceClientImpl(
      airbrakeNotifier,
      client,
      serviceDiscovery.serviceCluster(ServiceType.ELIZA),
      defaultContext,
      userThreadStatsForUserIdCache
    )
  }
}

