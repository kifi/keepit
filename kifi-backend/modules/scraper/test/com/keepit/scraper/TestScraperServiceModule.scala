package com.keepit.scraper

import com.keepit.common.cache.{ HashMapMemoryCacheModule, ScraperCacheModule }
import com.keepit.common.concurrent.DevForkJoinContextMonitorModule
import com.keepit.common.store.ScraperTestStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.scraper.embedly.DevEmbedlyModule
import com.keepit.scraper.fetcher.{ HttpFetcherModule, TestHttpFetcherModule }
import com.keepit.shoebox.{ TestShoeboxScraperClientModule, TestShoeboxServiceClientModule }

case class TestScraperServiceModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(HashMapMemoryCacheModule()),
  storeModule = ScraperTestStoreModule(),
  fjMonitorModule = DevForkJoinContextMonitorModule(),
  scrapeProcessorModule = TestScraperProcessorModule(),
  embedlyModule = DevEmbedlyModule()
) with CommonDevModule {
  val shoeboxServiceClientModule = TestShoeboxServiceClientModule()
  val shoeboxScraperClientModule = TestShoeboxScraperClientModule()
  val fetcherModule: HttpFetcherModule = TestHttpFetcherModule()
}
