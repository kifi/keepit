package com.keepit.abook

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ABookCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.common.store.ABookProdStoreModule

case class ABookProdModule() extends ABookModule(
  cacheModule = ABookCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  storeModule = ABookProdStoreModule()
) with CommonProdModule
