package com.keepit.common.cache

import scala.collection.concurrent.{TrieMap => ConcurrentMap}
import scala.concurrent._
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicInteger

import net.codingwell.scalaguice.ScalaModule
import net.sf.ehcache._
import net.sf.ehcache.config.CacheConfiguration

import com.google.inject.{Inject, Singleton}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging._
import com.keepit.common.time._
import com.keepit.serializer.{Serializer, BinaryFormat}
import com.keepit.common.logging.Access._

import play.api.Logger
import play.api.Plugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.modules.statsd.api.Statsd

class CacheStatistics @Inject() (global: GlobalCacheStatistics) extends Logging {

  private def incrCount(key: String, m: ConcurrentMap[String, AtomicInteger]) {
    m.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet()
  }

  def recordHit(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long) {
    val name = s"$cachePlugin.$namespace"
    incrCount(name, global.hitsMap)
    Statsd.increment(s"$name.hits")
    Statsd.timing(s"$name.hits", duration)
  }

  def recordMiss(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long) {
    val name = s"$cachePlugin.$namespace"
    incrCount(s"$name", global.missesMap)
    Statsd.increment(s"$name.misses")
    log.warn(s"Cache miss on key $fullKey in $cachePlugin")
  }

  def recordSet(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, duration: Long) {
    val name = s"$cachePlugin.$namespace"
    incrCount(s"$name", global.setsMap)
    Statsd.increment(s"$name.sets")
    Statsd.timing(s"$name.sets", duration)
  }
}
