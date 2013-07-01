package com.keepit.search

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, SearchCacheModule}
import com.keepit.common.{SearchBrowsingHistoryModule, SearchClickHistoryModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.healthcheck.HealthCheckProdModule
import com.keepit.common.store.SearchProdStoreModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.FortyTwoModule
import com.keepit.common.actor.ProdActorSystemModule
import com.keepit.common.zookeeper.ProdDiscoveryModule

case class SearchProdModule() extends SearchModule(

  // Common Functional Modules
  fortyTwoModule = FortyTwoModule(),
  cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ProdShoeboxServiceClientModule(),
  clickHistoryModule = SearchClickHistoryModule(),
  browsingHistoryModule = SearchBrowsingHistoryModule(),
  actorSystemModule = ProdActorSystemModule(),
  discoveryModule = ProdDiscoveryModule(),
  healthCheckModule = HealthCheckProdModule(),
  storeModule = SearchProdStoreModule(),
  httpClientModule = ProdHttpClientModule(),

  // Search Functional Modules
  indexModule = ProdIndexModule(),
  searchConfigModule = SearchConfigModule(),
  resultFeedbackModule = ProdResultFeedbackModule()
)
