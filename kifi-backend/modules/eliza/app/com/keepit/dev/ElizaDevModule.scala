package com.keepit.dev

import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.ProdFortyTwoModule
import com.keepit.common.actor.DevActorSystemModule
import com.keepit.common.zookeeper.DevDiscoveryModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.eliza.ElizaModule
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.cache.HashMapMemoryCacheModule


case class ElizaDevModule() extends ElizaModule(

  // Common Functional Modules
  fortyTwoModule = ProdFortyTwoModule(),
  cacheModule = ElizaCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ProdShoeboxServiceClientModule(),
  actorSystemModule = DevActorSystemModule(),
  discoveryModule = DevDiscoveryModule(),
  healthCheckModule = ProdHealthCheckModule(),
  httpClientModule = ProdHttpClientModule()

  // Eliza Functional Modules
)

