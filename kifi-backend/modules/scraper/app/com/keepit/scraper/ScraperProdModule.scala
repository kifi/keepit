package com.keepit.scraper

import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.inject.CommonProdModule
import com.keepit.common.cache.EhCacheCacheModule
import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.store.ScraperProdStoreModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.rover.fetcher.ProdHttpFetcherModule
import com.keepit.scraper.embedly.ProdEmbedlyModule
import com.keepit.scraper.fetcher.DeprecatedHttpFetcherImplModule

case class ScraperProdModule() extends ScraperModule(
  userActionsModule = ProdRemoteUserActionsHelperModule(),
  cacheModule = ScraperCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ScraperProdStoreModule(),
  fjMonitorModule = ProdForkJoinContextMonitorModule(),
  embedlyModule = ProdEmbedlyModule()
) with CommonProdModule {
  val fetcherModule = ProdHttpFetcherModule()
  val deprecatedFetcherModule = DeprecatedHttpFetcherImplModule()
}
