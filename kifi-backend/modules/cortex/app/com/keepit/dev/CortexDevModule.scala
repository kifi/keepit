package com.keepit.dev

import com.keepit.common.controller.{ DevRemoteUserActionsHelperModule }
import com.keepit.cortex.nlp.NLPDevModule
import com.keepit.inject.CommonDevModule
import com.keepit.cortex.CortexModule
import com.keepit.cortex.store._
import com.keepit.cortex.CortexDevModelModule
import com.keepit.common.cache.CortexCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.cortex.models.lda.LDAInfoStoreDevModule
import com.keepit.cortex.dbmodel.CortexDataIngestionDevModule

case class CortexDevModule() extends CortexModule(
  userActionsModule = DevRemoteUserActionsHelperModule(),
  cacheModule = CortexCacheModule(HashMapMemoryCacheModule()),
  commonStoreModule = CortexCommonDevStoreModule(),
  commitInfoModule = CommitInfoDevStoreModule(),
  featureStoreModule = FeatureDevStoreModule(),
  statModelStoreModule = StatModelDevStoreModule(),
  modelModule = CortexDevModelModule(),
  ldaInfoModule = LDAInfoStoreDevModule(),
  dataIngestionModule = CortexDataIngestionDevModule(),
  nlpModule = NLPDevModule()
) with CommonDevModule
