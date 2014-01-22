package com.keepit.graph

import com.keepit.graph.database.NeoGraphModule
import com.keepit.inject.{ConfigurationModule, FortyTwoModule}
import com.keepit.common.cache.CacheModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.common.actor.ActorSystemModule
import com.keepit.common.zookeeper.DiscoveryModule
import com.keepit.common.healthcheck.HealthCheckModule
import com.keepit.common.net.HttpClientModule

abstract class GraphModule(

  // Common Functional Modules
  val fortyTwoModule: FortyTwoModule,
  val cacheModule: CacheModule,
  val shoeboxServiceClientModule: ShoeboxServiceClientModule,
  val actorSystemModule: ActorSystemModule,
  val discoveryModule: DiscoveryModule,
  val healthCheckModule: HealthCheckModule,
  val httpClientModule: HttpClientModule,

  // Graph Functional Modules
  val neoGraphModule: NeoGraphModule

  ) extends ConfigurationModule(
    fortyTwoModule,
    cacheModule,
    shoeboxServiceClientModule,
    actorSystemModule,
    discoveryModule,
    healthCheckModule,
    httpClientModule,
    
    // Graph Functional Modules
    neoGraphModule
  )
