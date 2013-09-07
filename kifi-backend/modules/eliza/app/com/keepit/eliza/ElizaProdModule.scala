package com.keepit.eliza

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ElizaCacheModule}
import com.keepit.inject.CommonProdModule
import com.keepit.realtime.ElizaUrbanAirshipModule

case class ElizaProdModule() extends ElizaModule(
  cacheModule = ElizaCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  urbanAirshipModule = ElizaUrbanAirshipModule()
) with CommonProdModule
