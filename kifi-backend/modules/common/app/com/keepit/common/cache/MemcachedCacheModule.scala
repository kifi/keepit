package com.keepit.common.cache

import net.codingwell.scalaguice.ScalaModule
import net.spy.memcached.{AddrUtil, MemcachedClient}

import com.google.inject.{Provides, Singleton}
import com.keepit.inject.AppScoped

import play.api.Play
import play.api.Play.current

class MemcachedCacheModule() extends ScalaModule {
  def configure(): Unit = {
    bind[FortyTwoCachePlugin].to[MemcachedCache].in[AppScoped]
  }

  @Singleton
  @Provides
  def spyMemcachedClient(): MemcachedClient = {
    if (Play.isTest) throw new IllegalStateException("memcached client should not be loaded in test!")

    System.setProperty("net.spy.log.LoggerImpl", "com.keepit.common.cache.MemcachedSlf4JLogger")

    current.configuration.getString("elasticache.config.endpoint").map { endpoint =>
      new MemcachedClient(AddrUtil.getAddresses(endpoint))
    }.getOrElse(throw new RuntimeException("Bad configuration for memcached: missing host(s)"))
  }
}
