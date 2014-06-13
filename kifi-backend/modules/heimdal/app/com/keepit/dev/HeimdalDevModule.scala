package com.keepit.dev

import com.keepit.heimdal.{HeimdalQueueDevModule, HeimdalModule, DevMongoModule}
import com.keepit.inject.CommonDevModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.cache.HeimdalCacheModule

case class HeimdalDevModule() extends HeimdalModule(
  cacheModule = HeimdalCacheModule(HashMapMemoryCacheModule()),
  mongoModule = DevMongoModule(),
  heimdalQueueModule = HeimdalQueueDevModule()
) with CommonDevModule {
}
