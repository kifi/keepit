package com.keepit.cortex

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType


case class CortexProdModule() extends CortexModule with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SEARCH :: ServiceType.SHOEBOX :: Nil
  }
}
