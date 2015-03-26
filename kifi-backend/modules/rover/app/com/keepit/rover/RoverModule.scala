package com.keepit.rover

import com.keepit.common.controller.UserActionsModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.rover.manager.FetchQueueModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType
import com.keepit.rover.common.cache.RoverCacheModule
import com.keepit.rover.common.store.RoverStoreModule

case class RoverServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.ROVER
  val servicesToListenOn = ServiceType.SHOEBOX :: Nil
}

trait RoverModule extends ConfigurationModule with CommonServiceModule {

  // Common Functional Modules
  val userActionsModule: UserActionsModule
  val cacheModule: RoverCacheModule
  val storeModule: RoverStoreModule

  // Rover Functional Modules
  val fetchQueueModule: FetchQueueModule
  val slickModule = RoverSlickModule()
  val pluginModule: RoverPluginModule = RoverPluginModule()

  // Service clients
  val serviceTypeModule = RoverServiceTypeModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
}
