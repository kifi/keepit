package com.keepit.common.cache

import net.codingwell.scalaguice.ScalaModule

import com.keepit.inject.AppScoped

class EhCacheCacheModule() extends ScalaModule {
  def configure(): Unit = {
    bind[InMemoryCachePlugin].to[EhCacheCache].in[AppScoped]
  }
}

