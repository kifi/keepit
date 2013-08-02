package com.keepit.bender

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, BenderCacheModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.ProdFortyTwoModule
import com.keepit.common.actor.ProdActorSystemModule
import com.keepit.common.zookeeper.ProdDiscoveryModule

case class BenderProdModule() extends BenderModule(

  // Common Functional Modules
  fortyTwoModule = ProdFortyTwoModule(),
  cacheModule = BenderCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),
  shoeboxServiceClientModule = ProdShoeboxServiceClientModule(),
  actorSystemModule = ProdActorSystemModule(),
  discoveryModule = ProdDiscoveryModule(),
  healthCheckModule = ProdHealthCheckModule(),
  httpClientModule = ProdHttpClientModule()

  // Bender Functional Modules
)
