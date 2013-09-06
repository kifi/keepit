package com.keepit.search

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, SearchCacheModule}
import com.keepit.common.{SearchBrowsingHistoryModule, SearchClickHistoryModule}
import com.keepit.common.store.SearchProdStoreModule
import com.keepit.inject.CommonProdModule

case class SearchProdModule() extends SearchModule(

  // Common Functional Modules
  cacheModule = SearchCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  storeModule = SearchProdStoreModule(),

  // Search Functional Modules
  clickHistoryModule = SearchClickHistoryModule(),
  browsingHistoryModule = SearchBrowsingHistoryModule(),
  indexModule = ProdIndexModule(),
  searchConfigModule = SearchConfigModule(),
  resultFeedbackModule = ProdResultFeedbackModule()
)with CommonProdModule
