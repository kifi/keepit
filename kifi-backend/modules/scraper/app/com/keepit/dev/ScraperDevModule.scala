package com.keepit.dev

import com.keepit.common.cache.ScraperCacheModule
import com.keepit.scraper.fetcher.DevHttpFetcherModule
import com.keepit.scraper.{ DevScraperProcessorModule, ScraperModule }
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ScraperDevStoreModule
import com.keepit.common.concurrent.DevForkJoinContextMonitorModule
import com.keepit.scraper.embedly.DevEmbedlyModule

case class ScraperDevModule() extends ScraperModule(
  cacheModule = ScraperCacheModule(HashMapMemoryCacheModule()),
  storeModule = ScraperDevStoreModule(),
  fjMonitorModule = DevForkJoinContextMonitorModule(),
  scrapeProcessorModule = DevScraperProcessorModule(),
  embedlyModule = DevEmbedlyModule()
) with CommonDevModule {
  val fetcherModule = DevHttpFetcherModule()
}
