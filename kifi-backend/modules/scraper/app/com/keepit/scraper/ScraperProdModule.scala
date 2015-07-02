package com.keepit.scraper

import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.inject.CommonProdModule
import com.keepit.common.cache.EhCacheCacheModule
import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.rover.fetcher.ProdHttpFetcherModule

case class ScraperProdModule() extends ScraperModule(
  userActionsModule = ProdRemoteUserActionsHelperModule(),
  cacheModule = ScraperCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  fjMonitorModule = ProdForkJoinContextMonitorModule()
) with CommonProdModule {
  val fetcherModule = ProdHttpFetcherModule()
}
