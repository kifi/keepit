package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.curator.CuratorModule
import com.keepit.common.cache.CuratorCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.heimdal.DevHeimdalServiceClientModule

case class CuratorDevModule() extends CuratorModule(
  cacheModule = CuratorCacheModule(HashMapMemoryCacheModule())
) with CommonDevModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val graphServiceClientModule = ProdGraphServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val heimdalServiceClientModule = DevHeimdalServiceClientModule()
  val searchServiceClientModule = ProdSearchServiceClientModule()
}

