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
import com.keepit.common.logging.Access.CACHE
import com.keepit.common.logging._
import com.keepit.common.time._
import com.keepit.serializer.{Serializer, BinaryFormat}
import com.keepit.common.logging.{AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access._

import play.api.Logger
import play.api.Plugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.modules.statsd.api.Statsd

case class CacheSizeLimitExceededException(msg:String) extends Exception(msg)

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] {
  val stats: CacheStatistics
  val accessLog: AccessLog

  val repo: FortyTwoCachePlugin
  val serializer: Serializer[T]

  protected[cache] def getFromInnerCache(key: K): Option[Option[T]] = {
    val timer = accessLog.timer(CACHE)
    val valueOpt = try repo.get(key.toString) catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed fetching key $key from $repo")))
        None
    }
    decodeValue(key, valueOpt, timer)
  }

  private[this] def decodeValue(key: K, valueOpt: Option[Any], timer: AccessLogTimer): Option[Option[T]] = {
    try {
      val objOpt = valueOpt.map(serializer.reads)
      val namespace = key.namespace
      objOpt match {
        case Some(_) => {
          val duration = if (repo.logAccess) {
            accessLog.add(timer.done(space = s"${repo.toString}.${namespace}", key = key.toString, result = "HIT")).duration
          } else {
            timer.duration
          }
          stats.recordHit(repo.toString, repo.logAccess, namespace, key.toString, duration)
          stats.recordHit("Cache", false, namespace, key.toString, duration)
        }
        case None => {
          val duration = if (repo.logAccess) {
            accessLog.add(timer.done(space = s"${repo.toString}.${namespace}", key = key.toString, result = "MISS")).duration
          } else {
            timer.duration
          }
          stats.recordMiss(repo.toString, repo.logAccess, namespace, key.toString, duration)
          if (outerCache isEmpty) stats.recordMiss("Cache", false, namespace, key.toString, duration)
        }
      }
      objOpt
    } catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed deserializing key $key from $repo, got raw value $valueOpt")))
        repo.remove(key.toString)
        None
    }
  }

  protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, Option[T]] = {
    val timer = accessLog.timer(CACHE)
    val valueMap = try repo.bulkGet(keys.map{_.toString}) catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed fetching key $keys from $repo")))
        Map.empty[String, Option[T]]
    }
    if (repo.logAccess) {
      keys.headOption.foreach{ key =>
        accessLog.add(timer.done(space = s"${repo.toString}.${key.namespace}", key = keys mkString ",", method = "BULK_GET"))
      }
    }
    keys.foldLeft(Map.empty[K, Option[T]]){ (m, key) =>
      val objOpt = decodeValue(key, valueMap.get(key.toString), timer)
      objOpt match {
        case Some(obj) => m + (key -> obj)
        case None => m
      }
    }
  }

  protected[cache] def setInnerCache(key: K, valueOpt: Option[T]): Unit = {
    val timer = accessLog.timer(CACHE)
    try {
      val properlyBoxed = serializer.writes(valueOpt) match {
            case (isDefined: Boolean, x: java.lang.Byte) => (isDefined, x.byteValue())
            case (isDefined: Boolean, x: java.lang.Short) => (isDefined, x.shortValue())
            case (isDefined: Boolean, x: java.lang.Integer) => (isDefined, x.intValue())
            case (isDefined: Boolean, x: java.lang.Long) => (isDefined, x.longValue())
            case (isDefined: Boolean, x: java.lang.Float) => (isDefined, x.floatValue())
            case (isDefined: Boolean, x: java.lang.Double) => (isDefined, x.doubleValue())
            case (isDefined: Boolean, x: java.lang.Character) => (isDefined, x.charValue())
            case (isDefined: Boolean, x: java.lang.Boolean) => (isDefined, x.booleanValue())
            case (false, _) => (false, null)
            case x: scala.Array[Byte] => x // we only support byte[]
            case x: JsValue => Json.stringify(x)
            case x: String => x
          }
      val keyS = key.toString
      // workaround for memcached-specific 1M size limit
      properlyBoxed match {
//        case s:String => {
//          if (s.length + keyS.length > 400000) { // imprecise -- convert to (utf-8) byte[] TODO: compress if we do need to cache large data
//            repo.remove(keyS)
//            throw new CacheSizeLimitExceededException(s"KV(string) not cached: key.len=${keyS.length} ($keyS) val.len=${s.length} (${s.take(100)})")
//          }
//        }
        case a:Array[Byte] => {
          if (a.length + keyS.length > 900000) {
            repo.remove(keyS)
            throw new CacheSizeLimitExceededException(s"KV(byte[]) not cached: key.len=${keyS.length} ($keyS) val.len=${a.length}")
          }
        }
        case _ => // ignore
      }
      var ttlInSeconds = ttl match {
        case _ : Duration.Infinite => 0
        case _ => ttl.toSeconds.toInt
      }
      repo.set(keyS, properlyBoxed, ttlInSeconds)
      val namespace = key.namespace
      val duration = if (repo.logAccess) {
        accessLog.add(timer.done(space = s"${repo.toString}.${namespace}", key = key.toString, result = "SET")).duration
      } else {
        timer.duration
      }
      stats.recordSet(repo.toString, repo.logAccess, namespace, key.toString, duration)
      if (outerCache isEmpty) stats.recordSet("Cache", false, namespace, key.toString, duration)
    } catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed setting key $key in $repo")))
        throw e
    }
  }

  def remove(key: K) {
    try repo.remove(key.toString) catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed removing key $key from $repo")))
        None
    }
    outerCache map {outer => outer.remove(key)}
  }
}

object FortyTwoCacheFactory {
  def apply[K <: Key[T], T](
      innerToOuterPluginSettings: Seq[(FortyTwoCachePlugin, Duration, Serializer[T])],
      stats: CacheStatistics,
      accessLog: AccessLog): Option[FortyTwoCacheImpl[K, T]] =
    innerToOuterPluginSettings.foldRight[Option[FortyTwoCacheImpl[K, T]]](None) {
      case ((innerPlugin, shorterTTL, nextSerializer), outer) =>
        Some(new FortyTwoCacheImpl[K, T](stats, accessLog, innerPlugin, shorterTTL, nextSerializer, outer))
    }
}

class FortyTwoCacheImpl[K <: Key[T], T](
  val stats: CacheStatistics,
  val accessLog: AccessLog,
  val repo: FortyTwoCachePlugin,
  val ttl: Duration,
  val serializer: Serializer[T],
  override val outerCache: Option[ObjectCache[K, T]]
) extends FortyTwoCache[K, T] {

  // Constructor using a distinct serializer for each cache plugin
  def this(
      stats: CacheStatistics, accessLog: AccessLog,
      innerMostPluginSettings: (FortyTwoCachePlugin, Duration, Serializer[T]),
      innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration, Serializer[T])*) =
    this(stats, accessLog,
      innerMostPluginSettings._1, innerMostPluginSettings._2, innerMostPluginSettings._3,
      FortyTwoCacheFactory[K, T](innerToOuterPluginSettings, stats, accessLog))

  // Constructor using the same serializer for each cache plugin
  def this(
      stats: CacheStatistics, accessLog: AccessLog,
      innermostPluginSettings: (FortyTwoCachePlugin, Duration),
      innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(serializer: Serializer[T]) =
    this(stats, accessLog,
      (innermostPluginSettings._1, innermostPluginSettings._2, serializer), innerToOuterPluginSettings.map {case (plugin, ttl) => (plugin, ttl, serializer)}:_*)
}

abstract class JsonCacheImpl[K <: Key[T], T](
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: Format[T])
  extends FortyTwoCacheImpl[K, T](stats, accessLog,
    innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer(formatter))

abstract class BinaryCacheImpl[K <: Key[T], T](
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: BinaryFormat[T])
  extends FortyTwoCacheImpl[K, T](stats, accessLog,
    innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer(formatter))

abstract class PrimitiveCacheImpl[K <: Key[P], P <: AnyVal](
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends FortyTwoCacheImpl[K, P](stats, accessLog,
    innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer[P])

abstract class StringCacheImpl[K <: Key[String]](
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends FortyTwoCacheImpl[K, String](stats, accessLog,
    innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer.string)
