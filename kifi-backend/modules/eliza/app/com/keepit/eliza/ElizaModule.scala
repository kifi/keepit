package com.keepit.eliza

import com.keepit.common.cache.{ ElizaCacheModule, CacheModule }
import com.keepit.common.controller.UserActionsModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.search.SearchServiceClientModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.realtime.UrbanAirshipModule
import com.keepit.heimdal.HeimdalServiceClientModule
import com.keepit.abook.ABookServiceClientModule
import com.keepit.common.store.{ ElizaStoreModule, StoreModule }
import com.keepit.scraper.ScraperServiceClientModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class ElizaServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.ELIZA
  val servicesToListenOn = ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: ServiceType.SCRAPER :: Nil
}

trait ElizaModule extends ConfigurationModule with CommonServiceModule {

  // Common Functional Modules
  val userActionsModule: UserActionsModule
  val cacheModule: ElizaCacheModule
  val urbanAirshipModule: UrbanAirshipModule
  val storeModule: ElizaStoreModule

  // Service clients
  val serviceTypeModule = ElizaServiceTypeModule()
  val searchServiceClientModule: SearchServiceClientModule
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val elizaServiceClientModule: ElizaServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule
  val abookServiceClientModule: ABookServiceClientModule
  val scraperServiceClientModule: ScraperServiceClientModule

  val secureSocialModule = RemoteSecureSocialModule()
  val elizaSlickModule = ElizaSlickModule()
}
