package com.keepit.cortex

import com.keepit.common.cache.CacheModule
import com.keepit.common.controller.UserActionsModule
import com.keepit.cortex.nlp.NLPModule
import com.keepit.cortex.store.{ CortexCommonStoreModule, StatModelStoreModule }
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.rover.ProdRoverServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.cortex.models.lda.LDAInfoStoreModule
import com.keepit.cortex.dbmodel.CortexDataIngestionModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class CortexServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.CORTEX
  val servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.GRAPH :: ServiceType.HEIMDAL :: ServiceType.ROVER :: Nil
}

abstract class CortexModule(
    val userActionsModule: UserActionsModule,
    val cacheModule: CacheModule,
    val commonStoreModule: CortexCommonStoreModule,
    val statModelStoreModule: StatModelStoreModule,
    val modelModule: CortexModelModule,
    val ldaInfoModule: LDAInfoStoreModule,
    val dataIngestionModule: CortexDataIngestionModule,
    val queueModule: CortexQueueModule,
    val nlpModule: NLPModule) extends ConfigurationModule with CommonServiceModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val roverServiceClientModule = ProdRoverServiceClientModule()
  val cortexSlickModule = CortexSlickModule()
  val serviceTypeModule = CortexServiceTypeModule()
}
