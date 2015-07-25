package com.keepit.dev

import com.keepit.common.controller.DevRemoteUserActionsHelperModule
import com.keepit.heimdal.{ DevAnalyticsModule, DevDelightedModule, HeimdalQueueDevModule, HeimdalModule }
import com.keepit.helprank.DevReKeepStatsUpdaterModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.cache.HeimdalCacheModule

case class HeimdalDevModule() extends HeimdalModule(
  userActionsModule = DevRemoteUserActionsHelperModule(),
  cacheModule = HeimdalCacheModule(HashMapMemoryCacheModule()),
  analyticsModule = DevAnalyticsModule(),
  heimdalQueueModule = HeimdalQueueDevModule(),
  rekeepStatsUpdaterModule = DevReKeepStatsUpdaterModule(),
  delightedModule = DevDelightedModule()
) with CommonDevModule {
}
