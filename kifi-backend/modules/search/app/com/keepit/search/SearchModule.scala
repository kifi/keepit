package com.keepit.search

import com.keepit.common.cache.CacheModule
import com.keepit.common.store.StoreModule
import com.keepit.eliza.ElizaServiceClientModule
import com.keepit.heimdal.HeimdalServiceClientModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.search.index.IndexModule
import com.keepit.search.spellcheck.SpellCorrectorModule
import com.keepit.search.tracker.TrackingModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule

abstract class SearchModule(

  // Common Functional Modules
  val cacheModule: CacheModule,
  val storeModule: StoreModule,

  // Search Functional Modules
  val indexModule: IndexModule,
  val trackingModule: TrackingModule,
  val spellModule: SpellCorrectorModule

) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val searchServiceClientModule: SearchServiceClientModule
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val elizaServiceClientModule: ElizaServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule

  val secureSocialModule = RemoteSecureSocialModule()
  val searchConfigModule = SearchConfigModule()

}
