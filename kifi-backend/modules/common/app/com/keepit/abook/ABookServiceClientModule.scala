package com.keepit.abook

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.{ServiceClient, ServiceType}
import play.api.Play._
import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}

trait ABookServiceClientModule extends ScalaModule

case class ProdABookServiceClientModule() extends ABookServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def ABookServiceClient(client: HttpClient,
                         serviceDiscovery: ServiceDiscovery,
                         serviceClientBinder: ScalaMultibinder[ServiceClient],
                         airbrakeNotifier: AirbrakeNotifier): ABookServiceClient = {
    val abook = new ABookServiceClientImpl(
      airbrakeNotifier,
      client,
      serviceDiscovery.serviceCluster(ServiceType.ABOOK)
    )
    serviceClientBinder.addBinding().toInstance(abook)
    abook

}
