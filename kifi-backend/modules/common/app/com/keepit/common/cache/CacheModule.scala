package com.keepit.common.cache

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{ Provides, Singleton }
import net.spy.memcached.{ AddrUtil, MemcachedClient }
import play.api.Play
import play.api.Play._

abstract class CacheModule(cachePluginModules: CachePluginModule*) extends ScalaModule {
  def configure() {
    cachePluginModules.foreach(install)
  }
}

trait CachePluginModule extends ScalaModule

case class EhCacheCacheModule() extends CachePluginModule {
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

case class HashMapMemoryCacheModule(bindsFortyTwoCache: Boolean = true) extends CachePluginModule {
  def configure {
    if (bindsFortyTwoCache) // make optional to facilitate local testing
      bind[FortyTwoCachePlugin].to[HashMapMemoryCache]
    bind[InMemoryCachePlugin].to[HashMapMemoryCache]
  }
}

case class MemcachedCacheModule() extends CachePluginModule {
  def configure {
    bind[FortyTwoCachePlugin].to[HashMapMemoryCache]
  }

  //  def configure(): Unit = {
  //    bind[FortyTwoCachePlugin].to[MemcachedCache].in[AppScoped]
  //  }
  //
  //  @Singleton
  //  @Provides
  //  def spyMemcachedClient(): MemcachedClientProvider = {
  //    if (Play.isTest) throw new IllegalStateException("memcached client should not be loaded in test!")
  //    new MemcachedClientProvider()
  //  }
}
