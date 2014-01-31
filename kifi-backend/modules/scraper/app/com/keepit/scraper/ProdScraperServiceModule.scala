package com.keepit.scraper

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ScraperCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.common.store.ScraperProdStoreModule
import com.keepit.common.zookeeper.{ServiceDiscovery, ProdDiscoveryModule}
import com.keepit.common.service.ServiceType
import com.keepit.common.net._
import com.google.inject.{Provider, Provides}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.AccessLog
import com.keepit.common.controller.MidFlightRequests
import com.keepit.common.cache.EhCacheCacheModule
import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.amazon.MyAmazonInstanceInfo
import scala.Some
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.common.store.ScraperProdStoreModule

case class ProdScraperServiceModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ScraperProdStoreModule(),
  scrapeProcessorModule = ProdScraperProcessorModule()
) with CommonProdModule {

  //override val httpClientModule = new ScraperHttpClientModule()

  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: Nil
  }
}

class ScraperHttpClientModule extends ProdHttpClientModule {
  @Provides
  override def httpClientProvider(airbrake: Provider[AirbrakeNotifier],
       accessLog: AccessLog, serviceDiscovery: ServiceDiscovery,
       fastJsonParser: FastJsonParser, midFlightRequests: MidFlightRequests,
       myInstanceInfo: MyAmazonInstanceInfo): HttpClient = {
    val ecu = myInstanceInfo.info.instantTypeInfo.ecu
    val callTimeouts = CallTimeouts(responseTimeout = Some(100000), maxWaitTime = Some(40000 / ecu), maxJsonParseTime = Some(20000 / ecu))
    new HttpClientImpl(airbrake = airbrake, accessLog = accessLog, serviceDiscovery = serviceDiscovery,
      fastJsonParser = fastJsonParser, midFlightRequests = midFlightRequests, callTimeouts = callTimeouts)
  }

}
