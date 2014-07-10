package com.keepit.abook

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.{ ServiceClient, ServiceType }
import play.api.Play._
import net.codingwell.scalaguice.{ ScalaMultibinder, ScalaModule }
import play.api.Configuration._

case class ABookUploadConf(
  timeoutThreshold: Int, // minutes
  batchSize: Int)

trait ABookServiceClientModule extends ScalaModule

case class ProdABookServiceClientModule() extends ABookServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def ABookServiceClient(client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier): ABookServiceClient = {
    new ABookServiceClientImpl(
      airbrakeNotifier,
      client,
      serviceDiscovery.serviceCluster(ServiceType.ABOOK)
    )
  }

  @Singleton
  @Provides
  def abookUploadConf: ABookUploadConf = {
    val conf = current.configuration.getConfig("abook.upload").getOrElse(empty)
    ABookUploadConf(
      conf.getInt("timeout.threshold").getOrElse(30),
      conf.getInt("batch.size").getOrElse(200))
  }
}
