package com.keepit.abook

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ABookCacheModule}
import com.keepit.inject.CommonProdModule

case class ABookProdModule() extends ABookModule(
  cacheModule = ABookCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
) with CommonProdModule
