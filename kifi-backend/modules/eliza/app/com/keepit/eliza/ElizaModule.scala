package com.keepit.eliza

import com.keepit.common.cache.CacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}

abstract class ElizaModule(
  // Common Functional Modules
  val cacheModule: CacheModule,
  val secureSocialModule: SecureSocialModule,

  // Eliza Functional Modules
  val elizaSlickModule: ElizaSlickModule
) extends ConfigurationModule with CommonServiceModule
