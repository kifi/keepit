package com.keepit.maven

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._

trait MavenServiceClientModule extends ScalaModule

case class ProdMavenServiceClientModule() extends MavenServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def mavenServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier): MavenServiceClient = {
    new MavenServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.MAVEN),
      client,
      airbrakeNotifier
    )
  }
}
