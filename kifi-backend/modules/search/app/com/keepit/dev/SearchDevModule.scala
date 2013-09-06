package com.keepit.dev

import com.keepit.search._
import com.keepit.common.cache.SearchCacheModule
import com.keepit.common.SearchBrowsingHistoryModule
import com.keepit.common.SearchClickHistoryModule
import com.keepit.search.SearchConfigModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.store.SearchDevStoreModule
import com.keepit.inject.CommonDevModule

case class SearchDevModule() extends SearchModule(

  // Common Functional Modules
  cacheModule = SearchCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  storeModule = SearchDevStoreModule(),

  // Search Functional Modules
  clickHistoryModule = SearchClickHistoryModule(),
  browsingHistoryModule = SearchBrowsingHistoryModule(),
  indexModule = DevIndexModule(),
  searchConfigModule = SearchConfigModule(),
  resultFeedbackModule = DevResultFeedbackModule()
) with CommonDevModule

