package com.keepit.rover

import com.keepit.common.cache.{ MemcachedCacheModule, EhCacheCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.inject.CommonProdModule
import com.keepit.rover.common.cache.RoverCacheModule
import com.keepit.rover.fetcher.{ ProdHttpFetcherModule, HttpFetcherModule }
import com.keepit.rover.manager.{ ProdRoverQueueModule, RoverQueueModule }
import com.keepit.rover.store.RoverProdStoreModule

case class RoverProdModule() extends RoverModule with CommonProdModule {
  val userActionsModule = ProdRemoteUserActionsHelperModule()
  val cacheModule = RoverCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
  val storeModule = RoverProdStoreModule()
  val queueModule: RoverQueueModule = ProdRoverQueueModule()
  val httpFetcherModule: HttpFetcherModule = ProdHttpFetcherModule()
}
