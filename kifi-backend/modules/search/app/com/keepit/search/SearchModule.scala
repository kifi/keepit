package com.keepit.search

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.store.StoreModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.common.{SearchBrowsingHistoryModule, SearchClickHistoryModule}

abstract class SearchModule(

  // Common Functional Modules
  val cacheModule: CacheModule,
  val storeModule: StoreModule,

  // Search Functional Modules
  val indexModule: IndexModule,
  val resultFeedbackModule: ResultFeedbackModule

) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()

  val secureSocialModule = RemoteSecureSocialModule()

  val searchConfigModule = SearchConfigModule()
  val clickHistoryModule = SearchClickHistoryModule()
  val browsingHistoryModule = SearchBrowsingHistoryModule()
}
