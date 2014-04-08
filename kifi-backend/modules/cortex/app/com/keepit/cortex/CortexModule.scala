package com.keepit.cortex

import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.cortex.store.{CommitInfoStoreModule, FeatureStoreModule, StatModelStoreModule, CortexCommonStoreModule}


abstract class CortexModule(
  val commonStoreModule: CortexCommonStoreModule,
  val commitInfoModule: CommitInfoStoreModule,
  val featureStoreModuel: FeatureStoreModule,
  val statModelStoreModuel: StatModelStoreModule,
  val modelModuel: CortexModelModule
) extends ConfigurationModule with CommonServiceModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
}
