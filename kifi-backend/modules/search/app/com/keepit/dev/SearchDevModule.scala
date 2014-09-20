package com.keepit.dev

import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.heimdal.DevHeimdalServiceClientModule
import com.keepit.search._
import com.keepit.common.cache.SearchCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.store.SearchDevStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.search.spellcheck.SpellCorrectorModule
import com.keepit.search.tracker.DevTrackingModule
import com.keepit.search.index.DevIndexModule
import com.keepit.common.util.PlayAppConfigurationModule

case class SearchDevModule() extends SearchModule with CommonDevModule {

  // Common Functional Modules
  val cacheModule = SearchCacheModule(HashMapMemoryCacheModule())
  val storeModule = SearchDevStoreModule()

  // Search Functional Modules
  val indexModule = DevIndexModule()
  val trackingModule = DevTrackingModule()
  val spellModule = SpellCorrectorModule()

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val distributedSearchServiceClientModule = ProdDistributedSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = DevHeimdalServiceClientModule()
  val configurationModule = PlayAppConfigurationModule()
}

