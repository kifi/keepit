package com.keepit.dev

import com.keepit.search._
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.cache.SearchCacheModule
import com.keepit.common.SearchBrowsingHistoryModule
import com.keepit.common.SearchClickHistoryModule
import com.keepit.search.SearchConfigModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.store.SearchDevStoreModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.ProdFortyTwoModule
import com.keepit.common.actor.DevActorSystemModule
import com.keepit.common.zookeeper.DevDiscoveryModule

case class SearchDevModule() extends SearchModule(

  // Common Functional Modules
  fortyTwoModule = ProdFortyTwoModule(),
  cacheModule = SearchCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ProdShoeboxServiceClientModule(),
  clickHistoryModule = SearchClickHistoryModule(),
  browsingHistoryModule = SearchBrowsingHistoryModule(),
  actorSystemModule = DevActorSystemModule(),
  discoveryModule = DevDiscoveryModule(),
  healthCheckModule = ProdHealthCheckModule(),
  storeModule = SearchDevStoreModule(),
  httpClientModule = ProdHttpClientModule(),

  // Search Functional Modules
  indexModule = DevIndexModule(),
  searchConfigModule = SearchConfigModule(),
  resultFeedbackModule = DevResultFeedbackModule()
)

