package com.keepit.eliza

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.search.SearchServiceClientModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.realtime.UrbanAirshipModule
import com.keepit.heimdal.HeimdalServiceClientModule
import com.keepit.abook.ABookServiceClientModule
import com.keepit.common.store.StoreModule
import com.keepit.common.queue.SimpleQueueModule
import com.keepit.scraper.ScraperServiceClientModule

abstract class ElizaModule(
    // Common Functional Modules
    val cacheModule: CacheModule,
    val urbanAirshipModule: UrbanAirshipModule,
    val storeModule: StoreModule) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val searchServiceClientModule: SearchServiceClientModule
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val elizaServiceClientModule: ElizaServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule
  val abookServiceClientModule: ABookServiceClientModule
  val scraperServiceClientModule: ScraperServiceClientModule

  val secureSocialModule = RemoteSecureSocialModule()
  val elizaSlickModule = ElizaSlickModule()
}
