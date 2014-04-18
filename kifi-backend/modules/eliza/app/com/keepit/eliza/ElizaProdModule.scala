package com.keepit.eliza

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ElizaCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.realtime.ElizaUrbanAirshipModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.common.store.ElizaProdStoreModule
import com.keepit.common.queue.ProdSimpleQueueModule

case class ElizaProdModule() extends ElizaModule(
  cacheModule = ElizaCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  urbanAirshipModule = ElizaUrbanAirshipModule(),
  storeModule = ElizaProdStoreModule()
) with CommonProdModule {

  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: Nil
  }
  val elizaMailSettingsModule = ProdElizaMailNotificationsModule()
}
