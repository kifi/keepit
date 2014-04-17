package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.eliza.{DevElizaMailSettingsModule, ElizaModule}
import com.keepit.realtime.ElizaUrbanAirshipModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.store.ElizaDevStoreModule

case class ElizaDevModule() extends ElizaModule(
  cacheModule = ElizaCacheModule(HashMapMemoryCacheModule()),
  urbanAirshipModule = ElizaUrbanAirshipModule(),
  storeModule = ElizaDevStoreModule()
) with CommonDevModule {
  val elizaMailSettingsModule = DevElizaMailSettingsModule()
}

