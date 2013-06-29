package com.keepit.search

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, SearchCacheModule}
import com.keepit.common.{SearchBrowsingHistoryModule, SearchClickHistoryModule}
import com.keepit.shoebox.ShoeboxServiceClientImplModule
import com.keepit.module.{ProdActorSystemModule, ProdDiscoveryModule}
import com.keepit.common.healthcheck.HealthCheckProdModule
import com.keepit.common.store.SearchProdStoreModule

case class SearchProdModule() extends SearchModule(

  // Common Functional Modules
  cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ShoeboxServiceClientImplModule(),
  clickHistoryModule = SearchClickHistoryModule(),
  browsingHistoryModule = SearchBrowsingHistoryModule(),
  actorSystemModule = ProdActorSystemModule(),
  discoveryModule = ProdDiscoveryModule(),
  healthCheckModule = HealthCheckProdModule(),
  storeModule = SearchProdStoreModule(),

  // Search Functional Modules
  indexModule = ProdIndexModule(),
  searchConfigModule = SearchConfigModule(),
  resultFeedbackModule = ProdResultFeedbackModule()
)
