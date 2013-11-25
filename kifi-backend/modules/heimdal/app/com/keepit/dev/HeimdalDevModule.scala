package com.keepit.dev

import com.keepit.common.cache.HeimdalCacheModule
import com.keepit.heimdal.{ProdMongoModule, HeimdalModule, DevMongoModule}
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule


case class HeimdalDevModule() extends HeimdalModule(
  cacheModule = HeimdalCacheModule(HashMapMemoryCacheModule()),
  mongoModule = ProdMongoModule()
) with CommonDevModule {

}
