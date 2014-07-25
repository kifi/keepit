package com.keepit.scraper

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.common.cache.EhCacheCacheModule
import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.store.ScraperProdStoreModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.scraper.embedly.ProdEmbedlyModule
import com.keepit.scraper.fetcher.ProdHttpFetcherModule

case class ProdScraperServiceModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ScraperProdStoreModule(),
  fjMonitorModule = ProdForkJoinContextMonitorModule(),
  scrapeProcessorModule = ProdScraperProcessorModule(),
  embedlyModule = ProdEmbedlyModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule(ServiceType.SCRAPER, ServiceType.SHOEBOX :: Nil)
  val fetcherModule = ProdHttpFetcherModule()
}
