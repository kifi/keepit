package com.keepit.cortex

import com.keepit.common.cache.CacheModule
import com.keepit.common.controller.UserActionsModule
import com.keepit.cortex.nlp.NLPModule
import com.keepit.cortex.store.{ CommitInfoStoreModule, CortexCommonStoreModule, FeatureStoreModule, StatModelStoreModule }
import com.keepit.curator.ProdCuratorServiceClientModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.rover.ProdRoverServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.cortex.models.lda.LDAInfoStoreModule
import com.keepit.cortex.dbmodel.CortexDataIngestionModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class CortexServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.CORTEX
  val servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.GRAPH :: ServiceType.HEIMDAL :: ServiceType.CURATOR :: ServiceType.ROVER :: Nil
}

abstract class CortexModule(
    val userActionsModule: UserActionsModule,
    val cacheModule: CacheModule,
    val commonStoreModule: CortexCommonStoreModule,
    val commitInfoModule: CommitInfoStoreModule,
    val featureStoreModule: FeatureStoreModule,
    val statModelStoreModule: StatModelStoreModule,
    val modelModule: CortexModelModule,
    val ldaInfoModule: LDAInfoStoreModule,
    val dataIngestionModule: CortexDataIngestionModule,
    val nlpModule: NLPModule) extends ConfigurationModule with CommonServiceModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val curatorServiceClientModule = ProdCuratorServiceClientModule()
  val roverServiceClientModule = ProdRoverServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val cortexSlickModule = CortexSlickModule()
  val serviceTypeModule = CortexServiceTypeModule()
}
