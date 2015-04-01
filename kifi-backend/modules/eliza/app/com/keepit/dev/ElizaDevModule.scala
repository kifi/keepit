package com.keepit.dev

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.controller.{ DevRemoteUserActionsHelperModule }
import com.keepit.heimdal.DevHeimdalServiceClientModule
import com.keepit.inject.CommonDevModule
import com.keepit.eliza.{ ProdElizaServiceClientModule, DevElizaExternalEmailModule, ElizaModule }
import com.keepit.realtime.{ ElizaAppBoyModule, ElizaUrbanAirshipModule }
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.store.ElizaDevStoreModule
import com.keepit.scraper.ProdScraperServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule

case class ElizaDevModule() extends ElizaModule with CommonDevModule {
  val userActionsModule = DevRemoteUserActionsHelperModule()
  val cacheModule = ElizaCacheModule(HashMapMemoryCacheModule())
  val urbanAirshipModule = ElizaUrbanAirshipModule()
  val storeModule = ElizaDevStoreModule()
  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = DevHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val scraperServiceClientModule = ProdScraperServiceClientModule()

  val elizaMailSettingsModule = DevElizaExternalEmailModule()
}

