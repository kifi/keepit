package com.keepit.heimdal

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, HeimdalCacheModule}
import com.keepit.inject.CommonProdModule

case class HeimdalProdModule() extends HeimdalModule(
  cacheModule = HeimdalCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  mongoModule = ProdMongoModule()
) with CommonProdModule
