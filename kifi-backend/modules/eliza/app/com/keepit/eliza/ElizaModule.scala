package com.keepit.eliza

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.realtime.UrbanAirshipModule

abstract class ElizaModule(
  // Common Functional Modules
  val cacheModule: CacheModule,
  val urbanAirshipModule: UrbanAirshipModule

) extends ConfigurationModule with CommonServiceModule  {
  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()

  val secureSocialModule = RemoteSecureSocialModule()
  val elizaSlickModule = ElizaSlickModule()
}
