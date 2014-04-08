package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.cortex.CortexModule
import com.keepit.cortex.store._
import com.keepit.cortex.CortexDevModelModule


case class CortexDevModule() extends CortexModule(
  commonStoreModule = CortexCommonDevStoreModule(),
  commitInfoModule =  CommitInfoDevStoreModule(),
  featureStoreModuel = FeatureDevStoreModule(),
  statModelStoreModuel = StatModelDevStoreModule(),
  modelModuel =  CortexDevModelModule()
) with CommonDevModule
