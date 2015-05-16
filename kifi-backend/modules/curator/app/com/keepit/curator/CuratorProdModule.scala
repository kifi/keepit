package com.keepit.curator

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.cache.{ CuratorCacheModule, EhCacheCacheModule, MemcachedCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.curator.queue.{ ProdFeedDigestEmailQueueModule }
import com.keepit.curator.store.{ CuratorProdStoreModule }
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.inject.CommonProdModule
import com.keepit.rover.ProdRoverServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule

case class CuratorProdModule()
    extends CuratorModule(
      userActionsModule = ProdRemoteUserActionsHelperModule(),
      cacheModule = CuratorCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
    ) with CommonProdModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val graphServiceClientModule = ProdGraphServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val roverServiceClientModule = ProdRoverServiceClientModule()
  val curatorStoreModule = CuratorProdStoreModule()
  val feedDigestQueueModule = ProdFeedDigestEmailQueueModule()
}
