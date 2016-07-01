package com.keepit.heimdal

import com.keepit.common.cache.CacheModule
import com.keepit.common.controller.UserActionsModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.helprank.ReKeepStatsUpdaterModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class HeimdalServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.HEIMDAL
  val servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.ELIZA :: Nil
}

abstract class HeimdalModule(
    // Common Functional Modules
    val userActionsModule: UserActionsModule,
    val cacheModule: CacheModule,
    val analyticsModule: AnalyticsModule,
    val heimdalQueueModule: HeimdalQueueModule,
    val rekeepStatsUpdaterModule: ReKeepStatsUpdaterModule) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val serviceTypeModule = HeimdalServiceTypeModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalSlickModule = HeimdalSlickModule()
}
