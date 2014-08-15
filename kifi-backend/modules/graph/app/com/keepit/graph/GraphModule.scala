package com.keepit.graph

import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.graph.manager.{ GraphManagerPluginModule, GraphManagerModule }
import com.keepit.graph.common.store.GraphStoreModule
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class GraphServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.GRAPH
  val servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.ABOOK :: ServiceType.CORTEX :: Nil
}

trait GraphModule extends ConfigurationModule with CommonServiceModule {

  // Common Functional Modules
  val cacheModule: GraphCacheModule
  val storeModule: GraphStoreModule

  // Graph Functional Modules
  val graphManagerModule: GraphManagerModule
  val graphManagerPluginModule: GraphManagerPluginModule = GraphManagerPluginModule()

  // Service clients
  val serviceTypeModule = GraphServiceTypeModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
}
