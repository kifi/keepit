package com.keepit.shoebox

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import com.keepit.model.LibraryMembershipIdCache
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.ExecutionContext

trait ShoeboxServiceClientModule extends ScalaModule

case class ProdShoeboxServiceClientModule() extends ShoeboxServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def shoeboxServiceClient(
    executionContext: ExecutionContext,
    client: HttpClient,
    cacheProvider: ShoeboxCacheProvider,
    libraryMembershipCache: LibraryMembershipIdCache,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier): ShoeboxServiceClient = {
    new ShoeboxServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.SHOEBOX),
      client, airbrakeNotifier, cacheProvider, libraryMembershipCache, executionContext
    )
  }

}
