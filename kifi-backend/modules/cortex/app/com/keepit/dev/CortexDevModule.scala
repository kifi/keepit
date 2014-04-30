package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.cortex.CortexModule
import com.keepit.cortex.store._
import com.keepit.cortex.CortexDevModelModule
import com.keepit.common.cache.CortexCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.cortex.models.lda.LDAInfoStoreDevModule


case class CortexDevModule() extends CortexModule(
  cacheModule = CortexCacheModule(HashMapMemoryCacheModule()),
  commonStoreModule = CortexCommonDevStoreModule(),
  commitInfoModule =  CommitInfoDevStoreModule(),
  featureStoreModule = FeatureDevStoreModule(),
  statModelStoreModule = StatModelDevStoreModule(),
  modelModule =  CortexDevModelModule(),
  ldaInfoModule = LDAInfoStoreDevModule()
) with CommonDevModule
