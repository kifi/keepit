package com.keepit.heimdal

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.common.cache.EhCacheCacheModule
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.cache.HeimdalCacheModule

case class HeimdalProdModule() extends HeimdalModule(
  cacheModule = HeimdalCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  mongoModule = ProdMongoModule(),
  heimdalQueueModule = HeimdalQueueProdModule(),
  delightedModule = ProdDelightedModule()
) with CommonProdModule

