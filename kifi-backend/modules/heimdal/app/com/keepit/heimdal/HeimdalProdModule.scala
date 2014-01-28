package com.keepit.heimdal

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, HeimdalCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType

case class HeimdalProdModule() extends HeimdalModule(
  cacheModule = HeimdalCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  mongoModule = ProdMongoModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = Nil //is that right?
  }
}

