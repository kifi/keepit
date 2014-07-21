package com.keepit.heimdal

import com.keepit.common.cache.CacheModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule

abstract class HeimdalModule(
    // Common Functional Modules
    val cacheModule: CacheModule,
    val mongoModule: MongoModule,
    val heimdalQueueModule: HeimdalQueueModule,
    val delightedModule: DelightedModule) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val heimdalSlickModule = HeimdalSlickModule()
}
