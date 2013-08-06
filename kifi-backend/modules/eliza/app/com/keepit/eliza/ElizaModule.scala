package com.keepit.eliza

import com.keepit.common.cache.CacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.model.{BrowsingHistoryModule, ClickHistoryModule}
import com.keepit.common.healthcheck.HealthCheckModule
import com.keepit.common.store.StoreModule
import com.keepit.common.net.HttpClientModule
import com.keepit.inject.{FortyTwoModule, ConfigurationModule}
import com.keepit.common.actor.ActorSystemModule
import com.keepit.common.zookeeper.DiscoveryModule

abstract class ElizaModule(

  // Common Functional Modules
  val fortyTwoModule: FortyTwoModule,
  val cacheModule: CacheModule,
  val secureSocialModule: SecureSocialModule,
  val shoeboxServiceClientModule: ShoeboxServiceClientModule,
  val actorSystemModule: ActorSystemModule,
  val discoveryModule: DiscoveryModule,
  val healthCheckModule: HealthCheckModule,
  val httpClientModule: HttpClientModule,
  val elizaServiceClientModule: ElizaServiceClientModule


  // Eliza Functional Modules

) extends ConfigurationModule(
    fortyTwoModule,
    cacheModule,
    secureSocialModule,
    shoeboxServiceClientModule,
    actorSystemModule,
    discoveryModule,
    healthCheckModule,
    httpClientModule,
    elizaServiceClientModule
)
