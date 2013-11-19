package com.keepit.search

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, SearchCacheModule}
import com.keepit.common.store.SearchProdStoreModule
import com.keepit.inject.CommonProdModule
import com.keepit.search.spellcheck.SpellCorrectorModule

case class SearchProdModule() extends SearchModule(
  // Common Functional Modules
  cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = SearchProdStoreModule(),

  // Search Functional Modules
  indexModule = ProdIndexModule(),
  trackingModule = ProdTrackingModule(),
  spellModule = SpellCorrectorModule()
)with CommonProdModule
