package com.keepit.heimdal

import com.keepit.common.cache.CacheModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class HeimdalServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.HEIMDAL
  val servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.ELIZA :: Nil
}

abstract class HeimdalModule(
    // Common Functional Modules
    val cacheModule: CacheModule,
    val mongoModule: MongoModule,
    val heimdalQueueModule: HeimdalQueueModule,
    val delightedModule: DelightedModule) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val serviceTypeModule = HeimdalServiceTypeModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val heimdalSlickModule = HeimdalSlickModule()
}
