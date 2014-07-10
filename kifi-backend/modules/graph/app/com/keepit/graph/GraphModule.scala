package com.keepit.graph

import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.graph.manager.GraphManagerModule
import com.keepit.graph.common.store.GraphStoreModule
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.cortex.ProdCortexServiceClientModule

abstract class GraphModule(

    // Common Functional Modules
    val cacheModule: GraphCacheModule,
    val storeModule: GraphStoreModule,

    // Graph Functional Modules
    val graphManagerModule: GraphManagerModule) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
}
