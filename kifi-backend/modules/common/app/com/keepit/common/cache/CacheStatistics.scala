package com.keepit.common.cache

import scala.collection.concurrent.{ TrieMap => ConcurrentMap }
import scala.concurrent._
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import net.codingwell.scalaguice.ScalaModule
import net.sf.ehcache._
import net.sf.ehcache.config.CacheConfiguration
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging._
import com.keepit.common.time._
import com.keepit.serializer.{ Serializer, BinaryFormat }
import com.keepit.common.logging.Access._
import play.api.Logger
import play.api.Plugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._

object CacheStatistics {
  val cacheLog = Logger("com.keepit.cache")
}

class CacheStatistics @Inject() (global: GlobalCacheStatistics) extends Logging {
  import CacheStatistics.cacheLog
  private def incrCount(key: String, m: ConcurrentMap[String, AtomicInteger]) {
    m.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet()
  }

  def recordHit(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long): Unit = Future {
    val name = s"$cachePlugin.$namespace"
    incrCount(name, global.hitsMap)
    statsd.incrementOne(s"$name.hits", ONE_IN_THOUSAND)
    statsd.timing(s"$name.hits", duration, ONE_IN_TEN_THOUSAND)
  }(ExecutionContext.singleThread)

  def recordMiss(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long): Unit = Future {
    val name = s"$cachePlugin.$namespace"
    incrCount(s"$name", global.missesMap)
    statsd.incrementOne(s"$name.misses", ONE_IN_THOUSAND)
    cacheLog.warn(s"Cache miss on key $fullKey in $cachePlugin")
  }(ExecutionContext.singleThread)

  def recordSet(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long): Unit = Future {
    val name = s"$cachePlugin.$namespace"
    incrCount(s"$name", global.setsMap)
    statsd.incrementOne(s"$name.sets", ONE_IN_THOUSAND)
    statsd.timing(s"$name.sets", duration, ONE_IN_TEN_THOUSAND)
  }(ExecutionContext.singleThread)
}
