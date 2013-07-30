package com.keepit.dev

import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.cache.BenderCacheModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.ProdFortyTwoModule
import com.keepit.common.actor.DevActorSystemModule
import com.keepit.common.zookeeper.DevDiscoveryModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.bender.BenderModule
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.cache.HashMapMemoryCacheModule


case class BenderDevModule() extends BenderModule(

  // Common Functional Modules
  fortyTwoModule = ProdFortyTwoModule(),
  cacheModule = BenderCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ProdShoeboxServiceClientModule(),
  actorSystemModule = DevActorSystemModule(),
  discoveryModule = DevDiscoveryModule(),
  healthCheckModule = ProdHealthCheckModule(),
  httpClientModule = ProdHttpClientModule()

  // Bender Functional Modules
)

