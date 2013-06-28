package com.keepit.search

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, SearchCacheModule}
import com.keepit.common.{SearchBrowsingHistoryModule, SearchClickHistoryModule}
import com.keepit.shoebox.ShoeboxServiceClientImplModule
import com.keepit.module.ProdDiscoveryModule

case class SearchProdModule() extends SearchModule(

  // Common Functional Modules
  cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ShoeboxServiceClientImplModule(),
  clickHistoryModule = SearchClickHistoryModule(),
  browsingHistoryModule = SearchBrowsingHistoryModule(),
  discoveryModule = ProdDiscoveryModule(),

  // Search Functional Modules
  indexModule = IndexImplModule(),
  searchConfigModule = SearchConfigModule(),
  resultFeedbackModule = ResultFeedbackImplModule()
)
