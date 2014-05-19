package com.keepit.common.cache

import scala.collection.concurrent.{TrieMap => ConcurrentMap}
import scala.concurrent._
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import net.codingwell.scalaguice.ScalaModule
import net.sf.ehcache._
import net.sf.ehcache.config.CacheConfiguration
import com.google.inject.{Inject, Singleton}
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging._
import com.keepit.common.time._
import com.keepit.serializer.{Serializer, BinaryFormat}
import com.keepit.common.logging.Access._
import play.api.Logger
import play.api.Plugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._

class CacheStatistics @Inject() (global: GlobalCacheStatistics) extends Logging {

  private val cacheLog = Logger("com.keepit.cache")

  private def incrCount(key: String, m: ConcurrentMap[String, AtomicInteger]) {
    m.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet()
  }

  def recordHit(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long): Unit = future {
    val name = s"$cachePlugin.$namespace"
    incrCount(name, global.hitsMap)
    statsd.increment(s"$name.hits")
    statsd.timing(s"$name.hits", duration, ONE_IN_THOUSAND)
  }(ExecutionContext.singleThread)

  def recordMiss(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long): Unit = future {
    val name = s"$cachePlugin.$namespace"
    incrCount(s"$name", global.missesMap)
    statsd.increment(s"$name.misses")
    cacheLog.warn(s"Cache miss on key $fullKey in $cachePlugin")
  }(ExecutionContext.singleThread)

  def recordSet(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long): Unit = future {
    val name = s"$cachePlugin.$namespace"
    incrCount(s"$name", global.setsMap)
    statsd.increment(s"$name.sets")
    statsd.timing(s"$name.sets", duration, ONE_IN_THOUSAND)
  }(ExecutionContext.singleThread)
}
