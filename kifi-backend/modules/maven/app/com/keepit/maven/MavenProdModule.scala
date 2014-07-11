package com.keepit.maven

import com.keepit.common.cache.{ MavenCacheModule, EhCacheCacheModule, MemcachedCacheModule }
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.inject.CommonProdModule

case class MavenProdModule()
    extends MavenModule(
      cacheModule = MavenCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
    ) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule(ServiceType.MAVEN, ServiceType.SHOEBOX :: ServiceType.GRAPH :: ServiceType.CORTEX :: ServiceType.HEIMDAL :: Nil)
}
