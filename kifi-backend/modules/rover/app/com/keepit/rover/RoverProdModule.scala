package com.keepit.rover

import com.keepit.common.cache.{ MemcachedCacheModule, EhCacheCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.inject.CommonProdModule
import com.keepit.rover.common.cache.RoverCacheModule
import com.keepit.rover.common.store.RoverProdStoreModule
import com.keepit.rover.manager.{ ProdFetchQueueModule, FetchQueueModule }

case class RoverProdModule() extends RoverModule with CommonProdModule {
  val userActionsModule = ProdRemoteUserActionsHelperModule()
  val cacheModule = RoverCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
  val storeModule = RoverProdStoreModule()
  val fetchQueueModule: FetchQueueModule = ProdFetchQueueModule()
}
