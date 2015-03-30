package com.keepit.rover

import com.keepit.common.cache.{ MemcachedCacheModule, EhCacheCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.inject.CommonProdModule
import com.keepit.rover.common.cache.RoverCacheModule
import com.keepit.rover.fetcher.{ ProdHttpFetcherModule, HttpFetcherModule }
import com.keepit.rover.manager.{ ProdFetchQueueModule, FetchQueueModule }
import com.keepit.rover.store.RoverProdStoreModule

case class RoverProdModule() extends RoverModule with CommonProdModule {
  val userActionsModule = ProdRemoteUserActionsHelperModule()
  val cacheModule = RoverCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
  val storeModule = RoverProdStoreModule()
  val fetchQueueModule: FetchQueueModule = ProdFetchQueueModule()
  val httpFetcherModule: HttpFetcherModule = ProdHttpFetcherModule()
}
