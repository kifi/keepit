package com.keepit.dev

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.controller.DevRemoteUserActionsHelperModule
import com.keepit.curator.queue.DevFeedDigestEmailQueueModule
import com.keepit.curator.store.CuratorDevStoreModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.inject.CommonDevModule
import com.keepit.curator.CuratorModule
import com.keepit.common.cache.CuratorCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.rover.ProdRoverServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.heimdal.DevHeimdalServiceClientModule

case class CuratorDevModule() extends CuratorModule(
  userActionsModule = DevRemoteUserActionsHelperModule(),
  cacheModule = CuratorCacheModule(HashMapMemoryCacheModule())
) with CommonDevModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val graphServiceClientModule = ProdGraphServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val heimdalServiceClientModule = DevHeimdalServiceClientModule()
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val roverServiceClientModule = ProdRoverServiceClientModule()
  val curatorStoreModule = CuratorDevStoreModule()
  val feedDigestQueueModule = DevFeedDigestEmailQueueModule()
}

