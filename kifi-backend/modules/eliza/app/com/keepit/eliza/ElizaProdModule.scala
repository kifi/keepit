package com.keepit.eliza

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ElizaCacheModule}
import com.keepit.inject.CommonProdModule

case class ElizaProdModule() extends ElizaModule(
  cacheModule = ElizaCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
) with CommonProdModule
