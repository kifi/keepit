package com.keepit.eliza

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.realtime.UrbanAirshipModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.store.StoreModule
import com.keepit.common.queue.SimpleQueueModule
import com.keepit.scraper.ProdScraperServiceClientModule

abstract class ElizaModule(
  // Common Functional Modules
  val cacheModule: CacheModule,
  val urbanAirshipModule: UrbanAirshipModule,
  val storeModule: StoreModule
) extends ConfigurationModule with CommonServiceModule  {
  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val scraperServiceClientModule = ProdScraperServiceClientModule()

  val secureSocialModule = RemoteSecureSocialModule()
  val elizaSlickModule = ElizaSlickModule()
}
