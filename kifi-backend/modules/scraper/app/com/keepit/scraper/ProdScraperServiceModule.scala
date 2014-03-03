package com.keepit.scraper

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.common.cache.EhCacheCacheModule
import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.store.ScraperProdStoreModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule

case class ProdScraperServiceModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ScraperProdStoreModule(),
  fjMonitorModule = ProdForkJoinContextMonitorModule(),
  scrapeProcessorModule = ProdScraperProcessorModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: Nil
  }
}
