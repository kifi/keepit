package com.keepit.search.dev

import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.heimdal.DevHeimdalServiceClientModule
import com.keepit.rover.ProdRoverServiceClientModule
import com.keepit.search._
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.search.common.cache.SearchCacheModule
import com.keepit.search.common.store.SearchDevStoreModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.inject.CommonDevModule
import com.keepit.search.tracking.DevTrackingModule
import com.keepit.search.index.DevIndexModule
import com.keepit.common.util.PlayAppConfigurationModule

case class SearchDevModule() extends SearchModule with CommonDevModule {

  // Common Functional Modules
  val cacheModule = SearchCacheModule(HashMapMemoryCacheModule())
  val storeModule = SearchDevStoreModule()
  val userActionsModule = ProdRemoteUserActionsHelperModule()

  // Search Functional Modules
  val indexModule = DevIndexModule()
  val trackingModule = DevTrackingModule()

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val distributedSearchServiceClientModule = ProdDistributedSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = DevHeimdalServiceClientModule()
  val roverServiceClientModule = ProdRoverServiceClientModule()
  val configurationModule = PlayAppConfigurationModule()
}

