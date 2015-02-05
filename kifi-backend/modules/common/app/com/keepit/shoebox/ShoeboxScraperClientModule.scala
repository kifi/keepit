package com.keepit.shoebox

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import com.keepit.model.UrlPatternRulesAllCache
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule

trait ShoeboxScraperClientModule extends ScalaModule

case class ProdShoeboxScraperClientModule() extends ShoeboxScraperClientModule {

  def configure() {}

  @Singleton
  @Provides
  def shoeboxServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier,
    urlPatternRuleAllCache: UrlPatternRulesAllCache): ShoeboxScraperClient = {
    new ShoeboxScraperClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.SHOEBOX),
      client,
      airbrakeNotifier,
      urlPatternRuleAllCache
    )
  }

}
