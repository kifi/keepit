package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.graph.GraphModule
import com.keepit.graph.simple.{ SimpleGraphDevModule }
import com.keepit.graph.common.store.{ GraphDevStoreModule }
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule

case class GraphDevModule() extends GraphModule(
  cacheModule = GraphCacheModule(HashMapMemoryCacheModule()),
  storeModule = GraphDevStoreModule(),
  graphManagerModule = SimpleGraphDevModule()
) with CommonDevModule
