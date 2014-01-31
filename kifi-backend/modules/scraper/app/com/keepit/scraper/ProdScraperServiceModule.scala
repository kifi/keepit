package com.keepit.scraper

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ScraperCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.common.store.ScraperProdStoreModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType

case class ProdScraperServiceModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ScraperProdStoreModule(),
  scrapeProcessorModule = ProdScraperProcessorModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: Nil
  }
}

