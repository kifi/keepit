package com.keepit.dev

import com.keepit.common.cache.ElizaCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.eliza.ElizaModule
import com.keepit.common.cache.HashMapMemoryCacheModule

case class ElizaDevModule() extends ElizaModule(
  cacheModule = ElizaCacheModule(HashMapMemoryCacheModule())
) with CommonDevModule

