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

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] with CacheStatistics {
  val repo: FortyTwoCachePlugin
  val serializer: Serializer[T]

  protected[cache] def getFromInnerCache(key: K): Option[Option[T]] = {
    val timer = accessLog.timer(CACHE)
    val valueOpt = try repo.get(key.toString) catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed fetching key $key from $repo")))
        None
    }
    try {
      val objOpt = valueOpt.map(serializer.reads)
      objOpt match {
        case Some(_) => {
          recordHit(repo.toString, repo.logAccess, key.namespace, key.toString, time)
          recordHit("Cache", false, key.namespace, key.toString, time)
        }
        case None => {
          recordMiss(repo.toString, repo.logAccess, key.namespace, key.toString, time)
          if (outerCache isEmpty) recordMiss("Cache", false, key.namespace, key.toString, time)
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

  protected[cache] def setInnerCache(key: K, valueOpt: Option[T]): Unit = {
    val setStart = currentDateTime.getMillis()
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
      val setEnd = currentDateTime.getMillis()
      recordSet(repo.toString, repo.logAccess, key.namespace, key.toString, setEnd - setStart)
      if (outerCache isEmpty) recordSet("Cache", false, key.namespace, key.toString, setEnd - setStart)
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
  def apply[K <: Key[T], T](innerToOuterPluginSettings: Seq[(FortyTwoCachePlugin, Duration, Serializer[T])]): Option[FortyTwoCacheImpl[K, T]] =
    innerToOuterPluginSettings.foldRight[Option[FortyTwoCacheImpl[K, T]]](None) {
      case ((innerPlugin, shorterTTL, nextSerializer), outer) =>
        Some(new FortyTwoCacheImpl[K, T](innerPlugin, shorterTTL, nextSerializer, outer))
    }
}

class FortyTwoCacheImpl[K <: Key[T], T](
  val repo: FortyTwoCachePlugin,
  val ttl: Duration,
  val serializer: Serializer[T],
  override val outerCache: Option[ObjectCache[K, T]]
) extends FortyTwoCache[K, T] {

  // Constructor using a distinct serializer for each cache plugin
  def this(
      innerMostPluginSettings: (FortyTwoCachePlugin, Duration, Serializer[T]),
      innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration, Serializer[T])*) =
    this(innerMostPluginSettings._1, innerMostPluginSettings._2, innerMostPluginSettings._3, FortyTwoCacheFactory[K, T](innerToOuterPluginSettings))

  // Constructor using the same serializer for each cache plugin
  def this(
      innermostPluginSettings: (FortyTwoCachePlugin, Duration),
      innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(serializer: Serializer[T]) =
    this((innermostPluginSettings._1, innermostPluginSettings._2, serializer), innerToOuterPluginSettings.map {case (plugin, ttl) => (plugin, ttl, serializer)}:_*)
}

class JsonCacheImpl[K <: Key[T], T](
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: Format[T])
  extends FortyTwoCacheImpl[K, T](innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer(formatter))

class BinaryCacheImpl[K <: Key[T], T](
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: BinaryFormat[T])
  extends FortyTwoCacheImpl[K, T](innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer(formatter))

class PrimitiveCacheImpl[K <: Key[P], P <: AnyVal](
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends FortyTwoCacheImpl[K, P](innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer[P])

class StringCacheImpl[K <: Key[String]](
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends FortyTwoCacheImpl[K, String](innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer.string)
