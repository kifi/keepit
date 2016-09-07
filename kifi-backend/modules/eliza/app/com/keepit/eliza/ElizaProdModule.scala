package com.keepit.eliza

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.cache.{ EhCacheCacheModule, ElizaCacheModule, HashMapMemoryCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.common.store.ElizaProdStoreModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.inject.CommonProdModule
import com.keepit.rover.ProdRoverServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule

case class ElizaProdModule() extends ElizaModule with CommonProdModule {

  val userActionsModule = ProdRemoteUserActionsHelperModule()
  val cacheModule = ElizaCacheModule(HashMapMemoryCacheModule(), EhCacheCacheModule())
  val storeModule = ElizaProdStoreModule()

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val roverServiceClientModule = ProdRoverServiceClientModule()

  val elizaExternalEmailModule = ProdElizaExternalEmailModule()
}
