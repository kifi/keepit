package com.keepit.graph

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType


case class GraphProdModule() extends GraphModule with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.ABOOK :: Nil
  }
}
