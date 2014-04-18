package com.keepit.graph

import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.common.store.{GraphStoreModule, StoreModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.abook.ProdABookServiceClientModule

abstract class GraphModule(

  // Common Functional Modules
  //val cacheModule: CacheModule,
  val storeModule: GraphStoreModule,

  // Graph Functional Modules
  val graphManagerModule: GraphManagerModule

) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
}
