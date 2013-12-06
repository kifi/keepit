package com.keepit.dev

import com.keepit.common.cache.ScraperCacheModule
import com.keepit.scraper.{ScrapeProcessorImplModule, ScraperServiceModule}
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ScraperDevStoreModule

case class ScraperDevModule() extends ScraperServiceModule (
  cacheModule = ScraperCacheModule(HashMapMemoryCacheModule()),
  storeModule = ScraperDevStoreModule(),
  scrapeProcessorModule = ScrapeProcessorImplModule()
) with CommonDevModule {

}
