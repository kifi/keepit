package com.keepit.common.cache

import net.codingwell.scalaguice.ScalaModule

class CacheModule extends ScalaModule {
  def configure() {
    install(new MemcachedCacheModule)
    install(new EhCacheCacheModule)
  }

}
