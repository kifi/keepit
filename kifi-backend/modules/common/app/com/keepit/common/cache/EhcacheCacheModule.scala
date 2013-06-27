package com.keepit.common.cache

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.inject.AppScoped

case class EhCacheCacheModule() extends ScalaModule {
  def configure(): Unit = {
    bind[InMemoryCachePlugin].to[EhCacheCache].in[AppScoped]
  }

  @Singleton
  @Provides
  def ehCacheConfiguration() = {
    val config = new EhCacheConfiguration()
    config.setName("fortytwo")
    config.setMaxBytesLocalHeap("100m")
    config.setOverflowToOffHeap(false) // Not in use, BigMemory dependency to be resolved + JVM to be configured
    config.setMaxBytesLocalOffHeap("1g")
    config
  }
}

