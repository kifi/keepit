package com.keepit.search

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.store.StoreModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.search.spellcheck.SpellCorrectorModule
import com.keepit.search.tracker.TrackingModule

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
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()

  val secureSocialModule = RemoteSecureSocialModule()

  val searchConfigModule = SearchConfigModule()
}
