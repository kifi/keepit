package com.keepit.common.cache

import com.tzavellas.sse.guice.ScalaModule

class CacheModule extends ScalaModule {
  def configure() {
    install(new MemcachedCacheModule)
    install(new EhCacheCacheModule)
  }

}
