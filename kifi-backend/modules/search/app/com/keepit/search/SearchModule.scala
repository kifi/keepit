package com.keepit.search

import com.keepit.common.cache.CacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.model.{BrowsingHistoryModule, ClickHistoryModule}
import com.keepit.common.store.StoreModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}

abstract class SearchModule(

  // Common Functional Modules
  val cacheModule: CacheModule,
  val secureSocialModule: SecureSocialModule,
  val storeModule: StoreModule,

  // Search Functional Modules
  val clickHistoryModule: ClickHistoryModule,
  val browsingHistoryModule: BrowsingHistoryModule,
  val indexModule: IndexModule,
  val searchConfigModule: SearchConfigModule,
  val resultFeedbackModule: ResultFeedbackModule

) extends ConfigurationModule with CommonServiceModule
