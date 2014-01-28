package com.keepit.search

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, SearchCacheModule}
import com.keepit.common.store.SearchProdStoreModule
import com.keepit.inject.CommonProdModule
import com.keepit.search.spellcheck.SpellCorrectorModule
import com.keepit.search.tracker.ProdTrackingModule
import com.keepit.search.index.ProdIndexModule
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ProdDiscoveryModule

case class SearchProdModule() extends SearchModule(
  // Common Functional Modules
  cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = SearchProdStoreModule(),

  // Search Functional Modules
  indexModule = ProdIndexModule(),
  trackingModule = ProdTrackingModule(),
  spellModule = SpellCorrectorModule()
) with CommonProdModule  {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.HEIMDAL :: ServiceType.ELIZA :: Nil
  }
}

