package com.keepit.dev

import com.keepit.search._
import com.keepit.common.cache.SearchCacheModule
import com.keepit.search.SearchConfigModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.store.SearchDevStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.search.spellcheck.SpellCorrectorModule
import com.keepit.search.tracker.DevTrackingModule
import com.keepit.search.index.DevIndexModule

case class SearchDevModule() extends SearchModule(

  // Common Functional Modules
  cacheModule = SearchCacheModule(HashMapMemoryCacheModule()),
  storeModule = SearchDevStoreModule(),

  // Search Functional Modules
  indexModule = DevIndexModule(),
  trackingModule = DevTrackingModule(),
  spellModule = SpellCorrectorModule()
) with CommonDevModule

