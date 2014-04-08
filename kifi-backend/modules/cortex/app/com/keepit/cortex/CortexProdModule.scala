package com.keepit.cortex

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.cortex.store._


case class CortexProdModule()
extends CortexModule(
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
