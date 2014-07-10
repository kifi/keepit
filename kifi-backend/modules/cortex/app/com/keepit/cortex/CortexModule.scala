package com.keepit.cortex

import com.keepit.common.cache.CacheModule
import com.keepit.cortex.store.{ CommitInfoStoreModule, CortexCommonStoreModule, FeatureStoreModule, StatModelStoreModule }
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.cortex.models.lda.LDAInfoStoreModule
import com.keepit.cortex.dbmodel.CortexDataIngestionModule

abstract class CortexModule(
    val cacheModule: CacheModule,
    val commonStoreModule: CortexCommonStoreModule,
    val commitInfoModule: CommitInfoStoreModule,
    val featureStoreModule: FeatureStoreModule,
    val statModelStoreModule: StatModelStoreModule,
    val modelModule: CortexModelModule,
    val ldaInfoModule: LDAInfoStoreModule,
    val dataIngestionModule: CortexDataIngestionModule) extends ConfigurationModule with CommonServiceModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val cortexSlickModule = CortexSlickModule()
}
