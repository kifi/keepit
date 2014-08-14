package com.keepit.curator

import com.keepit.common.cache.CacheModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.search.SearchServiceClientModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.graph.GraphServiceClientModule
import com.keepit.cortex.CortexServiceClientModule
import com.keepit.heimdal.HeimdalServiceClientModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.zookeeper.{ ServiceTypeModule }
import com.keepit.common.service.ServiceType

case class CuratorServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.CURATOR
  val servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.GRAPH :: ServiceType.CORTEX :: ServiceType.HEIMDAL :: ServiceType.SEARCH :: Nil
}

abstract class CuratorModule(
    val cacheModule: CacheModule,
    val curatorTasksModule: CuratorTasksPluginModule = CuratorTasksPluginModule()) extends ConfigurationModule with CommonServiceModule {
  val serviceTypeModule = CuratorServiceTypeModule()
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val graphServiceClientModule: GraphServiceClientModule
  val cortexServiceClientModule: CortexServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule
  val searchServiceClientModule: SearchServiceClientModule
  val secureSocialModule = RemoteSecureSocialModule()
  val curatorSlickModule = CuratorSlickModule()
  val dbSequencingModule = CuratorDbSequencingModule()
}
