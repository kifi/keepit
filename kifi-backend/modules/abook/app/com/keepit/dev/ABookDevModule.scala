package com.keepit.dev

import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.cache.ABookCacheModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.ProdFortyTwoModule
import com.keepit.common.actor.DevActorSystemModule
import com.keepit.common.zookeeper.DevDiscoveryModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.abook.ABookModule
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ABookDevStoreModule

case class ABookDevModule() extends ABookModule (
  cacheModule = ABookCacheModule(HashMapMemoryCacheModule()),
  storeModule = ABookDevStoreModule()
) with CommonDevModule {

}
