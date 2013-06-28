package com.keepit.dev

import com.keepit.search._
import com.keepit.module.{LocalDiscoveryModule, DevActorSystemModule}
import com.keepit.shoebox.ShoeboxServiceClientImplModule
import com.keepit.common.cache.SearchCacheModule
import com.keepit.common.SearchBrowsingHistoryModule
import com.keepit.common.SearchClickHistoryModule
import com.keepit.search.SearchConfigModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.service.ServiceType

case class SearchDevModule() extends SearchModule(

  // Common Functional Modules
  cacheModule = SearchCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ShoeboxServiceClientImplModule(),
  clickHistoryModule = SearchClickHistoryModule(),
  browsingHistoryModule = SearchBrowsingHistoryModule(),
  actorSystemModule = DevActorSystemModule(),
  discoveryModule = LocalDiscoveryModule(ServiceType.DEV_MODE),

  // Search Functional Modules
  indexModule = DevIndexModule(),
  searchConfigModule = SearchConfigModule(),
  resultFeedbackModule = DevResultFeedbackModule()
)
