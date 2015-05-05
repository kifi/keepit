package com.keepit.search

import com.keepit.common.cache.CacheModule
import com.keepit.common.controller.UserActionsModule
import com.keepit.eliza.ElizaServiceClientModule
import com.keepit.heimdal.HeimdalServiceClientModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.rover.RoverServiceClientModule
import com.keepit.search.common.store.SearchStoreModule
import com.keepit.search.index.IndexModule
import com.keepit.search.tracking.TrackingModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class SearchServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.SEARCH
  val servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.HEIMDAL :: ServiceType.ELIZA :: ServiceType.ROVER :: Nil
}

trait SearchModule extends ConfigurationModule with CommonServiceModule {

  // Common Functional Modules
  val cacheModule: CacheModule
  val storeModule: SearchStoreModule
  val userActionsModule: UserActionsModule

  // Search Functional Modules
  val indexModule: IndexModule
  val trackingModule: TrackingModule

  // Service clients
  val serviceTypeModule = SearchServiceTypeModule()
  val searchServiceClientModule: SearchServiceClientModule
  val distributedSearchServiceClientModule: DistributedSearchServiceClientModule
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val elizaServiceClientModule: ElizaServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule
  val roverServiceClientModule: RoverServiceClientModule

  val secureSocialModule = RemoteSecureSocialModule()
  val searchConfigModule = SearchConfigModule()

}
