package com.keepit.eliza

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.cache.{ EhCacheCacheModule, ElizaCacheModule, MemcachedCacheModule }
import com.keepit.common.service.ServiceType
import com.keepit.common.store.ElizaProdStoreModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.inject.CommonProdModule
import com.keepit.realtime.ElizaUrbanAirshipModule
import com.keepit.scraper.ProdScraperServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule

case class ElizaProdModule() extends ElizaModule(
  cacheModule = ElizaCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  urbanAirshipModule = ElizaUrbanAirshipModule(),
  storeModule = ElizaProdStoreModule()
) with CommonProdModule {

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val scraperServiceClientModule = ProdScraperServiceClientModule()

  val elizaExternalEmailModule = ProdElizaExternalEmailModule()
}
