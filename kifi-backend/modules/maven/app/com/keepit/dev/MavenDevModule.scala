package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.maven.MavenModule
import com.keepit.common.cache.MavenCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule

case class MavenDevModule() extends MavenModule(
  cacheModule = MavenCacheModule(HashMapMemoryCacheModule())
) with CommonDevModule
