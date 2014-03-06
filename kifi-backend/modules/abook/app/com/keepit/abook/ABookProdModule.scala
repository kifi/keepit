package com.keepit.abook

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ABookCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.common.store.ABookProdStoreModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.common.queue.ProdSimpleQueueModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule

case class ABookProdModule() extends ABookModule(
  cacheModule = ABookCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ABookProdStoreModule(),
  contactsUpdaterPluginModule = ProdContactsUpdaterPluginModule(),
  sqsModule = ProdSimpleQueueModule()
) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: Nil
  }
}
