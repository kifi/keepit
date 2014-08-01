package com.keepit.dev

import com.keepit.common.cache.{ HashMapMemoryCacheModule, ScraperCacheModule }
import com.keepit.common.concurrent.DevForkJoinContextMonitorModule
import com.keepit.common.store.ScraperDevStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.scraper.embedly.DevEmbedlyModule
import com.keepit.scraper.fetcher.DevHttpFetcherModule
import com.keepit.scraper.{ DevScraperProcessorModule, ScraperServiceModule }
import com.keepit.shoebox.{ ProdShoeboxScraperClientModule, ProdShoeboxServiceClientModule }

case class ScraperDevModule() extends ScraperServiceModule(
  cacheModule = ScraperCacheModule(HashMapMemoryCacheModule()),
  storeModule = ScraperDevStoreModule(),
  fjMonitorModule = DevForkJoinContextMonitorModule(),
  scrapeProcessorModule = DevScraperProcessorModule(),
  embedlyModule = DevEmbedlyModule()
) with CommonDevModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val shoeboxScraperClientModule = ProdShoeboxScraperClientModule()
  val fetcherModule = DevHttpFetcherModule()
}
