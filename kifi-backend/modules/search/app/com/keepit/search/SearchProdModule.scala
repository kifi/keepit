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

case class SearchProdModule() extends SearchModule(
  // Common Functional Modules
  cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = SearchProdStoreModule(),

  // Search Functional Modules
  indexModule = ProdIndexModule(),
  trackingModule = ProdTrackingModule(),
  spellModule = SpellCorrectorModule()
) with CommonProdModule {
  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
}

