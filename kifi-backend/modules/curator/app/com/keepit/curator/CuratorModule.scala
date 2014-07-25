package com.keepit.curator

import com.keepit.common.cache.CacheModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.graph.GraphServiceClientModule
import com.keepit.cortex.CortexServiceClientModule
import com.keepit.heimdal.HeimdalServiceClientModule
import com.keepit.social.RemoteSecureSocialModule

abstract class CuratorModule(
    val cacheModule: CacheModule,
    val seedIngestionPluginModule: SeedIngestionPluginModule = SeedIngestionPluginModule()) extends ConfigurationModule with CommonServiceModule {
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val graphServiceClientModule: GraphServiceClientModule
  val cortexServiceClientModule: CortexServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule
  val secureSocialModule = RemoteSecureSocialModule()
  val curatorSlickModule = CuratorSlickModule()
  val dbSequencingModule = CuratorDbSequencingModule()
}
