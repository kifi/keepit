package com.keepit.cortex

import com.keepit.common.cache.{CortexCacheModule, EhCacheCacheModule, MemcachedCacheModule}
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.cortex.store._
import com.keepit.inject.CommonProdModule
import com.keepit.cortex.models.lda.LDAInfoStoreProdModule


case class CortexProdModule()
extends CortexModule(
  cacheModule = CortexCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  commonStoreModule = CortexCommonProdStoreModule(),
  commitInfoModule =  CommitInfoProdStoreModule(),
  featureStoreModule = FeatureProdStoreModule(),
  statModelStoreModule = StatModelProdStoreModule(),
  modelModule =  CortexProdModelModule(),
  ldaInfoModule = LDAInfoStoreProdModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule(ServiceType.CORTEX, ServiceType.SHOEBOX :: Nil)
}
