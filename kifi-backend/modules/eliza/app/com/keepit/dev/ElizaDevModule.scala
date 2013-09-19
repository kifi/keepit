package com.keepit.dev

import com.keepit.common.cache.ElizaCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.eliza.ElizaModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.realtime.ElizaUrbanAirshipModule

case class ElizaDevModule() extends ElizaModule(
  cacheModule = ElizaCacheModule(HashMapMemoryCacheModule()),
  urbanAirshipModule = ElizaUrbanAirshipModule()
) with CommonDevModule

