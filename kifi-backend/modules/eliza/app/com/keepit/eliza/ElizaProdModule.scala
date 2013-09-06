package com.keepit.eliza

import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ElizaCacheModule}
import com.keepit.inject.CommonProdModule

case class ElizaProdModule() extends ElizaModule(
  // Common Functional Modules
  cacheModule = ElizaCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),

  // Eliza Functional Modules
  elizaSlickModule = ElizaSlickModule()
) with CommonProdModule
