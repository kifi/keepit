package com.keepit.eliza

import com.keepit.common.cache.{ ElizaCacheModule }
import com.keepit.common.controller.UserActionsModule
import com.keepit.rover.{ RoverServiceClientModule }
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.search.SearchServiceClientModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.heimdal.HeimdalServiceClientModule
import com.keepit.abook.ABookServiceClientModule
import com.keepit.common.store.{ ElizaStoreModule }
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class ElizaServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.ELIZA
  val servicesToListenOn = ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: ServiceType.ROVER :: Nil
}

trait ElizaModule extends ConfigurationModule with CommonServiceModule {

  // Common Functional Modules
  val userActionsModule: UserActionsModule
  val cacheModule: ElizaCacheModule
  val storeModule: ElizaStoreModule

  // Service clients
  val serviceTypeModule = ElizaServiceTypeModule()
  val searchServiceClientModule: SearchServiceClientModule
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val elizaServiceClientModule: ElizaServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule
  val abookServiceClientModule: ABookServiceClientModule
  val roverServiceClientModule: RoverServiceClientModule

  val elizaSlickModule = ElizaSlickModule()
  val dbSequencingModule = ElizaDbSequencingModule()

  val tasksModule: ElizaTasksPluginModule = ElizaTasksPluginModule()
}
