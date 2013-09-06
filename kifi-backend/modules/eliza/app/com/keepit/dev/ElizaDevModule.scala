package com.keepit.dev

import com.keepit.common.cache.ElizaCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.eliza.{ElizaSlickModule, ElizaModule}
import com.keepit.common.cache.HashMapMemoryCacheModule

case class ElizaDevModule() extends ElizaModule(

  // Common Functional Modules
  cacheModule = ElizaCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = RemoteSecureSocialModule(),

  // Eliza Functional Modules
  elizaSlickModule = ElizaSlickModule()
) with CommonDevModule

