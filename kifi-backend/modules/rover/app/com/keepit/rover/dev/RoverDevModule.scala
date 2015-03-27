package com.keepit.rover.dev

import com.keepit.common.controller.DevRemoteUserActionsHelperModule
import com.keepit.inject.CommonDevModule
import com.keepit.rover.RoverModule
import com.keepit.rover.common.store.{ RoverDevStoreModule }
import com.keepit.rover.common.cache.RoverCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.rover.fetcher.{ DevHttpFetcherModule, HttpFetcherModule }
import com.keepit.rover.manager.{ DevFetchQueueModule, FetchQueueModule }

case class RoverDevModule() extends RoverModule with CommonDevModule {
  val userActionsModule = DevRemoteUserActionsHelperModule()
  val cacheModule = RoverCacheModule(HashMapMemoryCacheModule())
  val storeModule = RoverDevStoreModule()
  val fetchQueueModule: FetchQueueModule = DevFetchQueueModule()
  val httpFetcherModule: HttpFetcherModule = DevHttpFetcherModule()
}
