package com.keepit.curator

import scala.concurrent.ExecutionContext

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._

trait CuratorServiceClientModule extends ScalaModule

case class ProdCuratorServiceClientModule() extends CuratorServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def curatorServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    defaultContext: ExecutionContext,
    airbrakeNotifier: AirbrakeNotifier): CuratorServiceClient = {
    new CuratorServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.CURATOR),
      client,
      defaultContext,
      airbrakeNotifier
    )
  }
}
