package com.keepit.rover.dev

import com.keepit.common.controller.DevRemoteUserActionsHelperModule
import com.keepit.inject.CommonDevModule
import com.keepit.rover.RoverModule
import com.keepit.rover.common.cache.RoverCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.rover.fetcher.{ DevHttpFetcherModule, HttpFetcherModule }
import com.keepit.rover.manager.{ DevRoverQueueModule, RoverQueueModule }
import com.keepit.rover.store.RoverDevStoreModule

case class RoverDevModule() extends RoverModule with CommonDevModule {
  val userActionsModule = DevRemoteUserActionsHelperModule()
  val cacheModule = RoverCacheModule(HashMapMemoryCacheModule())
  val storeModule = RoverDevStoreModule()
  val queueModule: RoverQueueModule = DevRoverQueueModule()
  val httpFetcherModule: HttpFetcherModule = DevHttpFetcherModule()
}
