package com.keepit.dev

import com.keepit.common.cache.{ HashMapMemoryCacheModule, HeimdalCacheModule }
import com.keepit.common.controller.DevRemoteUserActionsHelperModule
import com.keepit.heimdal.{ HeimdalQueueDevModule, ProdAnalyticsModule, HeimdalModule }
import com.keepit.helprank.DevReKeepStatsUpdaterModule
import com.keepit.inject.CommonDevModule

case class HeimdalDevModule() extends HeimdalModule(
  userActionsModule = DevRemoteUserActionsHelperModule(),
  cacheModule = HeimdalCacheModule(HashMapMemoryCacheModule()),
  analyticsModule = ProdAnalyticsModule(),
  heimdalQueueModule = HeimdalQueueDevModule(),
  rekeepStatsUpdaterModule = DevReKeepStatsUpdaterModule()
) with CommonDevModule {
}
