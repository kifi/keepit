package com.keepit.common.cache

import com.tzavellas.sse.guice.ScalaModule
import com.keepit.inject.AppScoped

class EhCacheCacheModule() extends ScalaModule {
    def configure(): Unit = {
      bind[InMemoryFortyTwoCachePlugin].to[EhCacheCache].in[AppScoped]
    }
}

