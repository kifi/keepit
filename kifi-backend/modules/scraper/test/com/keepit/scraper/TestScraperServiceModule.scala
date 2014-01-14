package com.keepit.scraper

import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ScraperTestStoreModule

case class TestScraperServiceModule() extends ScraperServiceModule (
  cacheModule = ScraperCacheModule(HashMapMemoryCacheModule()),
  storeModule = ScraperTestStoreModule(),
  scrapeProcessorModule = TestScraperProcessorModule()
) with CommonDevModule {

}
