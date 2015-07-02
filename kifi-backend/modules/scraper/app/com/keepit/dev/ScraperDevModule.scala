package com.keepit.dev

import com.keepit.common.cache.ScraperCacheModule
import com.keepit.common.controller.DevRemoteUserActionsHelperModule
import com.keepit.rover.fetcher.DevHttpFetcherModule
import com.keepit.scraper.{ ScraperModule }
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.concurrent.DevForkJoinContextMonitorModule

case class ScraperDevModule() extends ScraperModule(
  userActionsModule = DevRemoteUserActionsHelperModule(),
  cacheModule = ScraperCacheModule(HashMapMemoryCacheModule()),
  fjMonitorModule = DevForkJoinContextMonitorModule()
) with CommonDevModule {
  val fetcherModule = DevHttpFetcherModule()
}
