package com.keepit.curator

import com.keepit.common.cache.{ CuratorCacheModule, EhCacheCacheModule, MemcachedCacheModule }
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.inject.CommonProdModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule

case class CuratorProdModule()
    extends CuratorModule(
      cacheModule = CuratorCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
    ) with CommonProdModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val graphServiceClientModule = ProdGraphServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val searchServiceClientModule = ProdSearchServiceClientModule()
}
