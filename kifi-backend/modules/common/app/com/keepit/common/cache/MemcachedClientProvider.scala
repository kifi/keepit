package com.keepit.common.cache

import java.util.concurrent.TimeUnit

import com.keepit.common.logging.Logging
import net.spy.memcached.{ AddrUtil, MemcachedClient }
import play.api.Play._

class MemcachedClientProvider() extends Logging {

  private var client = create()
  private var bulkClient = create()

  private var recreateCount = 0
  private var bulkRecreateCount = 0

  private val lock = new AnyRef
  private val bulkLock = new AnyRef

  private def create() = {
    System.setProperty("net.spy.log.LoggerImpl", "com.keepit.common.cache.MemcachedSlf4JLogger")

    current.configuration.getString("elasticache.config.endpoint").map { endpoint =>
      new MemcachedClient(AddrUtil.getAddresses(endpoint))
    }.getOrElse(throw new RuntimeException("Bad configuration for memcached: missing host(s)"))
  }

  def get(): MemcachedClient = {
    client
  }

  def getBulkClient(): MemcachedClient = {
    bulkClient
  }

  def recreate(old: MemcachedClient): Unit = {

    lock.synchronized {
      if (client == old) {
        client = create()
        recreateCount += 1
        log.info(s"new memached client created: ${client}")
        log.info(s"memcached client recreated ${recreateCount} times. shutting down old client ${old}")
        old.shutdown(1, TimeUnit.SECONDS)
      } else {
        log.info(s"trying to recreate client, but referring to a retried client ${old}. Ignored")
      }
    }

  }

  def recreateBulk(old: MemcachedClient): Unit = {

    bulkLock.synchronized {
      if (bulkClient == old) {
        bulkClient = create()
        bulkRecreateCount += 1
        log.info(s"new memached bulk client created: ${bulkClient}")
        log.info(s"memcached bulk client recreated ${bulkRecreateCount} times. shutting down old client ${old}")
        old.shutdown(1, TimeUnit.SECONDS)
      } else {
        log.info(s"trying to recreate bulk client, but referring to a retried client ${old}. Ignored")
      }
    }

  }
}
