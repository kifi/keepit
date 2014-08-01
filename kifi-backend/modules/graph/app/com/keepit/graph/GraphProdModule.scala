package com.keepit.graph

import com.keepit.common.cache.{ MemcachedCacheModule, EhCacheCacheModule }
import com.keepit.inject.CommonProdModule
import com.keepit.graph.simple.SimpleGraphProdModule
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.graph.common.store.GraphProdStoreModule

case class GraphProdModule() extends GraphModule with CommonProdModule {
  val cacheModule = GraphCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
  val storeModule = GraphProdStoreModule()
  val graphManagerModule = SimpleGraphProdModule()
}
