package com.keepit.heimdal

import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.helprank.ProdReKeepStatsUpdaterModule
import com.keepit.inject.CommonProdModule
import com.keepit.common.cache.EhCacheCacheModule
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.cache.HeimdalCacheModule

case class HeimdalProdModule() extends HeimdalModule(
  userActionsModule = ProdRemoteUserActionsHelperModule(),
  cacheModule = HeimdalCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  analyticsModule = ProdAnalyticsModule(),
  heimdalQueueModule = HeimdalQueueProdModule(),
  rekeepStatsUpdaterModule = ProdReKeepStatsUpdaterModule()
) with CommonProdModule

