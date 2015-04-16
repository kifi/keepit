package com.keepit.common.cache

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import com.keepit.common.logging.Logging
import net.spy.memcached.{ AddrUtil, MemcachedClient }
import play.api.Play._

class MemcachedClientProvider() extends Logging {

  private var client = create()

  private var recreateCount = 0
  private val qlock = new ReentrantLock()

  private def create() = {
    System.setProperty("net.spy.log.LoggerImpl", "com.keepit.common.cache.MemcachedSlf4JLogger")

    current.configuration.getString("elasticache.config.endpoint").map { endpoint =>
      new MemcachedClient(AddrUtil.getAddresses(endpoint))
    }.getOrElse(throw new RuntimeException("Bad configuration for memcached: missing host(s)"))
  }

  def get(): MemcachedClient = {
    qlock.synchronized {
      client
    }
  }

  def recreate(old: MemcachedClient): Unit = {
    qlock.lock()

    if (client == old) {
      client = create()
      recreateCount += 1
      log.info(s"memcached client recreated ${recreateCount} times")
      qlock.unlock()

      old.shutdown(1, TimeUnit.SECONDS)
    } else {
      qlock.unlock()
      log.info(s"trying to recreate client, but referring to a retried client. Ignored")
    }

  }
}
