package com.keepit.graph

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.graph.simple.SimpleGraphProdModule
import com.keepit.graph.common.store.GraphProdStoreModule


case class GraphProdModule() extends GraphModule(GraphProdStoreModule(), SimpleGraphProdModule()) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.ABOOK :: Nil
  }
}
