package com.keepit.search

import com.keepit.common.cache.{ EhCacheCacheModule, MemcachedCacheModule, SearchCacheModule }
import com.keepit.common.store.SearchProdStoreModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.inject.CommonProdModule
import com.keepit.search.spellcheck.SpellCorrectorModule
import com.keepit.search.tracker.ProdTrackingModule
import com.keepit.search.index.ProdIndexModule
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.util.PlayAppConfigurationModule

case class SearchProdModule() extends SearchModule with CommonProdModule {

  // Common Functional Modules
  val cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
  val storeModule = SearchProdStoreModule()

  // Search Functional Modules
  val indexModule = ProdIndexModule()
  val trackingModule = ProdTrackingModule()
  val spellModule = SpellCorrectorModule()

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val distributedSearchServiceClientModule = ProdDistributedSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val configurationModule = PlayAppConfigurationModule()
}

