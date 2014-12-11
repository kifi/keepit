package com.keepit.graph.dev

import com.keepit.common.controller.DevRemoteUserActionsHelperModule
import com.keepit.inject.CommonDevModule
import com.keepit.graph.GraphModule
import com.keepit.graph.simple.{ SimpleGraphDevModule }
import com.keepit.graph.common.store.{ GraphDevStoreModule }
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule

case class GraphDevModule() extends GraphModule with CommonDevModule {
  val userActionsModule = DevRemoteUserActionsHelperModule()
  val cacheModule = GraphCacheModule(HashMapMemoryCacheModule())
  val storeModule = GraphDevStoreModule()
  val graphManagerModule = SimpleGraphDevModule()
}
