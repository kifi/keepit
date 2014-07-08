package com.keepit.eliza

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ElizaCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.realtime.ElizaUrbanAirshipModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.common.store.ElizaProdStoreModule

case class ElizaProdModule() extends ElizaModule(
  cacheModule = ElizaCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  urbanAirshipModule = ElizaUrbanAirshipModule(),
  storeModule = ElizaProdStoreModule()
) with CommonProdModule {

  val discoveryModule = new ProdDiscoveryModule(ServiceType.ELIZA, ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: ServiceType.SCRAPER :: Nil)
  val elizaExternalEmailModule = ProdElizaExternalEmailModule()
}
