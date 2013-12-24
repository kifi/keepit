package com.keepit.scraper

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ScraperCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.common.store.ScraperProdStoreModule

case class ProdScraperServiceModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ScraperProdStoreModule(),
  scrapeProcessorModule = ProdScraperProcessorModule()
) with CommonProdModule
