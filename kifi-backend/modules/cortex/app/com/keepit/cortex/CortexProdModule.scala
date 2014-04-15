package com.keepit.cortex

import com.keepit.common.cache.{CortexCacheModule, EhCacheCacheModule, MemcachedCacheModule}
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.cortex.store._
import com.keepit.inject.CommonProdModule


case class CortexProdModule()
extends CortexModule(
  cacheModule = CortexCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  commonStoreModule = CortexCommonProdStoreModule(),
  commitInfoModule =  CommitInfoProdStoreModule(),
  featureStoreModuel = FeatureProdStoreModule(),
  statModelStoreModuel = StatModelProdStoreModule(),
  modelModuel =  CortexProdModelModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: Nil
  }
}
