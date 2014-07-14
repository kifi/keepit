package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.curator.CuratorModule
import com.keepit.common.cache.CuratorCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule

case class CuratorDevModule() extends CuratorModule(
  cacheModule = CuratorCacheModule(HashMapMemoryCacheModule())
) with CommonDevModule
