package com.keepit.common.cache

import java.util.concurrent.TimeUnit

import net.spy.memcached.{ AddrUtil, MemcachedClient }
import play.api.Play._

class MemcachedClientProvider() {

  private var client: MemcachedClient = create()

  private def create() = {
    System.setProperty("net.spy.log.LoggerImpl", "com.keepit.common.cache.MemcachedSlf4JLogger")

    current.configuration.getString("elasticache.config.endpoint").map { endpoint =>
      new MemcachedClient(AddrUtil.getAddresses(endpoint))
    }.getOrElse(throw new RuntimeException("Bad configuration for memcached: missing host(s)"))
  }

  def get(): MemcachedClient = {
    client
  }

  def recreate(old: MemcachedClient): Unit = {
    if (client == old) {
      client = create()
      old.shutdown(1, TimeUnit.SECONDS)
    }
  }
}
