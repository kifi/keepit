package com.keepit.cortex

import com.keepit.common.cache.{ CortexCacheModule, EhCacheCacheModule, MemcachedCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.cortex.nlp.NLPProdModule
import com.keepit.cortex.store._
import com.keepit.inject.CommonProdModule
import com.keepit.cortex.models.lda.LDAInfoStoreProdModule
import com.keepit.cortex.dbmodel.CortexDataIngestionProdModule

case class CortexProdModule()
  extends CortexModule(
    userActionsModule = ProdRemoteUserActionsHelperModule(),
    cacheModule = CortexCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
    commonStoreModule = CortexCommonProdStoreModule(),
    statModelStoreModule = StatModelProdStoreModule(),
    modelModule = CortexProdModelModule(),
    ldaInfoModule = LDAInfoStoreProdModule(),
    dataIngestionModule = CortexDataIngestionProdModule(),
    queueModule = CortexProdQueueModule(),
    nlpModule = NLPProdModule()
  ) with CommonProdModule
