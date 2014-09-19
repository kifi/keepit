package com.keepit.search

import com.keepit.common.cache.{ EhCacheCacheModule, MemcachedCacheModule, SearchCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.common.store.SearchProdStoreModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.inject.CommonProdModule
import com.keepit.search.spellcheck.SpellCorrectorModule
import com.keepit.search.tracker.ProdTrackingModule
import com.keepit.search.index.ProdIndexModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.util.PlayAppConfigurationModule

case class SearchProdModule() extends SearchModule(
  // Common Functional Modules
  userActionsModule = ProdRemoteUserActionsHelperModule(),
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
  val configurationModule = PlayAppConfigurationModule()
}

