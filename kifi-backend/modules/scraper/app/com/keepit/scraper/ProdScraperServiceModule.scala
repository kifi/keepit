package com.keepit.scraper

import com.keepit.common.cache.{ EhCacheCacheModule, MemcachedCacheModule, ScraperCacheModule }
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.common.service.ServiceType
import com.keepit.common.store.ScraperProdStoreModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.inject.CommonProdModule
import com.keepit.scraper.embedly.ProdEmbedlyModule
import com.keepit.scraper.fetcher.ProdHttpFetcherModule
import com.keepit.shoebox.{ ProdShoeboxScraperClientModule, ProdShoeboxServiceClientModule }

case class ProdScraperServiceModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ScraperProdStoreModule(),
  fjMonitorModule = ProdForkJoinContextMonitorModule(),
  scrapeProcessorModule = ProdScraperProcessorModule(),
  embedlyModule = ProdEmbedlyModule()
) with CommonProdModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val shoeboxScraperClientModule = ProdShoeboxScraperClientModule()
  val discoveryModule = new ProdDiscoveryModule(ServiceType.SCRAPER, ServiceType.SHOEBOX :: Nil)
  val fetcherModule = ProdHttpFetcherModule()
}
