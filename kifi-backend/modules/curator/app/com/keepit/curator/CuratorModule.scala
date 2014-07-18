package com.keepit.curator

import com.keepit.common.cache.CacheModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule

abstract class CuratorModule(
    val cacheModule: CacheModule,
    val seedIngestionPluginModule: SeedIngestionPluginModule = SeedIngestionPluginModule()) extends ConfigurationModule with CommonServiceModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val curatorSlickModule = CuratorSlickModule()
  val dbSequencingModule = CuratorDbSequencingModule()
}
