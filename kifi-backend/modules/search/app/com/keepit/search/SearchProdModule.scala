package com.keepit.search

import com.keepit.common.cache.{ EhCacheCacheModule, MemcachedCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.inject.CommonProdModule
import com.keepit.rover.ProdRoverServiceClientModule
import com.keepit.search.common.cache.SearchCacheModule
import com.keepit.search.common.store.SearchProdStoreModule
import com.keepit.search.tracking.ProdTrackingModule
import com.keepit.search.index.ProdIndexModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.util.PlayAppConfigurationModule

case class SearchProdModule() extends SearchModule with CommonProdModule {

  // Common Functional Modules
  val cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
  val storeModule = SearchProdStoreModule()
  val userActionsModule = ProdRemoteUserActionsHelperModule()

  // Search Functional Modules
  val indexModule = ProdIndexModule()
  val trackingModule = ProdTrackingModule()

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val distributedSearchServiceClientModule = ProdDistributedSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val roverServiceClientModule = ProdRoverServiceClientModule()
  val configurationModule = PlayAppConfigurationModule()
}

