package com.keepit.search

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.SearchCacheModule
import com.keepit.common.{SearchBrowsingHistoryModule, SearchClickHistoryModule}
import com.keepit.shoebox.ShoeboxServiceClientImplModule

case class SearchProdModule() extends SearchModule(

  // Common Functional Modules
  cacheModule = SearchCacheModule(),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ShoeboxServiceClientImplModule(),
  clickHistoryModule = SearchClickHistoryModule(),
  browsingHistoryModule = SearchBrowsingHistoryModule(),

  // Search Functional Modules
  indexModule = IndexImplModule(),
  searchConfigModule = SearchConfigImplModule(),
  resultFeedbackModule = ResultFeedbackImplModule()
)
