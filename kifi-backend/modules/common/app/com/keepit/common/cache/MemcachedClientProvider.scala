package com.keepit.common.cache

import java.util.concurrent.TimeUnit

import com.keepit.common.logging.Logging
import net.spy.memcached.{ AddrUtil, MemcachedClient }
import play.api.Play._

class MemcachedClientProvider() extends Logging {

  private var client = create()

  private var recreateCount = 0

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
      recreateCount += 1
      log.info(s"memcached client recreated ${recreateCount} times. shutting down client ${old}")
      old.shutdown(1, TimeUnit.SECONDS)
    } else {
      log.info(s"trying to recreate client, but referring to a retried client ${old}. Ignored")
    }

  }
}
