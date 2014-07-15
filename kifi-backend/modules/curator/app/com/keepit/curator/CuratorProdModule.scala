package com.keepit.curator

import com.keepit.common.cache.{ CuratorCacheModule, EhCacheCacheModule, MemcachedCacheModule }
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.inject.CommonProdModule

case class CuratorProdModule()
    extends CuratorModule(
      cacheModule = CuratorCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
    ) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule(ServiceType.CURATOR, ServiceType.SHOEBOX :: ServiceType.GRAPH :: ServiceType.CORTEX :: ServiceType.HEIMDAL :: Nil)
}
