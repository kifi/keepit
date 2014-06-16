package com.keepit.graph

import com.keepit.common.cache.{MemcachedCacheModule, EhCacheCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.graph.simple.SimpleGraphProdModule
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.graph.common.store.GraphProdStoreModule


case class GraphProdModule() extends GraphModule(
  cacheModule = GraphCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = GraphProdStoreModule(),
  graphManagerModule = SimpleGraphProdModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule(ServiceType.GRAPH, ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.ABOOK :: ServiceType.CORTEX :: Nil)
}
