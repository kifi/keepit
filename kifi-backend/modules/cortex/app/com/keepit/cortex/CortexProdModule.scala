package com.keepit.cortex

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.cortex.store._
import com.keepit.common.cache.CortexCacheModule


case class CortexProdModule()
extends CortexModule(
  cacheModule = CortexCacheModule(),
  commonStoreModule = CortexCommonProdStoreModule(),
  commitInfoModule =  CommitInfoProdStoreModule(),
  featureStoreModuel = FeatureProdStoreModule(),
  statModelStoreModuel = StatModelProdStoreModule(),
  modelModuel =  CortexProdModelModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SEARCH :: ServiceType.SHOEBOX :: Nil
  }
}
