package com.keepit.scraper

import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ScraperTestStoreModule
import com.keepit.common.concurrent.DevForkJoinContextMonitorModule
import com.keepit.scraper.embedly.DevEmbedlyModule
import com.keepit.scraper.fetcher.{ HttpFetcherModule, TestHttpFetcherModule }

case class TestScraperServiceModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(HashMapMemoryCacheModule()),
  storeModule = ScraperTestStoreModule(),
  fjMonitorModule = DevForkJoinContextMonitorModule(),
  scrapeProcessorModule = TestScraperProcessorModule(),
  embedlyModule = DevEmbedlyModule()
) with CommonDevModule {
  val fetcherModule: HttpFetcherModule = TestHttpFetcherModule()
}
