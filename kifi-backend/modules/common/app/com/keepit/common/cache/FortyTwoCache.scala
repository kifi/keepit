package com.keepit.common.cache

import scala.concurrent.duration._
import com.keepit.common.healthcheck.AirbrakeError
import com.keepit.common.logging.Access.CACHE
import com.keepit.serializer.Serializer
import com.keepit.common.logging.{ AccessLogTimer, AccessLog }
import play.api.libs.json._
import java.util.Random

case class CacheSizeLimitExceededException(msg: String) extends Exception(msg)

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] {
  val stats: CacheStatistics
  val accessLog: AccessLog

  val repo: FortyTwoCachePlugin
  val serializer: Serializer[T]

  private[this] val rnd = new Random

  protected[cache] def getFromInnerCache(key: K): ObjectState[T] = {
    val timer = accessLog.timer(CACHE)
    val valueOpt = try repo.get(key.toString) catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed fetching key $key from $repo")))
        None
    }
    decodeValue(key, valueOpt, timer)
  }

  private[this] def decodeValue(key: K, valueOpt: Option[Any], timer: AccessLogTimer): ObjectState[T] = {
    try {
      val namespace = key.namespace
      valueOpt.map(serializer.reads) match {
        case Some(obj) => {
          val duration = if (repo.logAccess) {
            accessLog.add(timer.done(space = s"${repo.toString}.${namespace}", key = key.toString, result = "HIT")).duration
          } else {
            timer.duration
          }
          stats.recordHit(repo.toString, repo.logAccess, namespace, key.toString, duration)
          stats.recordHit("Cache", false, namespace, key.toString, duration)
          Found(obj)
        }
        case None => {
          val duration = if (repo.logAccess) {
            accessLog.add(timer.done(space = s"${repo.toString}.${namespace}", key = key.toString, result = "MISS")).duration
          } else {
            timer.duration
          }
          stats.recordMiss(repo.toString, repo.logAccess, namespace, key.toString, duration)
          if (outerCache isEmpty) stats.recordMiss("Cache", false, namespace, key.toString, duration)
          NotFound()
        }
      }
    } catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed deserializing key $key from $repo, got raw value $valueOpt")))
        repo.remove(key.toString)
        NotFound()
    }
  }

  protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, ObjectState[T]] = {
    val timer = accessLog.timer(CACHE)
    val valueMap = try repo.bulkGet(keys.map { _.toString }) catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed fetching key $keys from $repo")))
        Map.empty[String, ObjectState[T]]
    }
    if (repo.logAccess) {
      keys.headOption.foreach { key =>
        accessLog.add(timer.done(space = s"${repo.toString}.${key.namespace}", key = keys mkString ",", method = "BULK_GET"))
      }
    }
    keys.foldLeft(Map.empty[K, ObjectState[T]]) { (m, key) =>
      m + (key -> decodeValue(key, valueMap.get(key.toString), timer))
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
        case a: Array[Byte] => {
          if (a.length + keyS.length > 900000) {
            repo.remove(keyS)
            throw new CacheSizeLimitExceededException(s"KV(byte[]) not cached: key.len=${keyS.length} ($keyS) val.len=${a.length}")
          }
        }
        case _ => // ignore
      }
      val ttlInSeconds = maxTTL match {
        case _: Duration.Infinite => 0
        case _ =>
          if (minTTL == maxTTL) {
            minTTL.toSeconds.toInt
          } else {
            val minTTLSeconds = minTTL.toSeconds.toInt
            val maxTTLSeconds = maxTTL.toSeconds.toInt
            minTTLSeconds + (rnd.nextDouble * (maxTTLSeconds - minTTLSeconds).toDouble).toInt
          }
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
    val timer = accessLog.timer(CACHE)
    try {
      repo.remove(key.toString)
      if (repo.logAccess) accessLog.add(timer.done(space = s"${repo.toString}.${key.namespace}", key = key.toString, result = "REMOVED"))
    } catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed removing key $key from $repo")))
        None
    }
    outerCache map { outer => outer.remove(key) }
  }
}

object FortyTwoCacheFactory {
  def apply[K <: Key[T], T](
    innerToOuterPluginSettings: Seq[(FortyTwoCachePlugin, Duration, Duration, Serializer[T])],
    stats: CacheStatistics,
    accessLog: AccessLog): Option[FortyTwoCacheImpl[K, T]] =
    innerToOuterPluginSettings.foldRight[Option[FortyTwoCacheImpl[K, T]]](None) {
      case ((innerPlugin, minTTL, maxTTL, nextSerializer), outer) =>
        Some(new FortyTwoCacheImpl[K, T](stats, accessLog, innerPlugin, minTTL, maxTTL, nextSerializer, outer))
    }
}

class FortyTwoCacheImpl[K <: Key[T], T](
    val stats: CacheStatistics,
    val accessLog: AccessLog,
    val repo: FortyTwoCachePlugin,
    val minTTL: Duration,
    val maxTTL: Duration,
    val serializer: Serializer[T],
    override val outerCache: Option[ObjectCache[K, T]]) extends FortyTwoCache[K, T] {

//  assert(minTTL.toMillis <= (30 days).toMillis, "minTTL too long")
//  assert(maxTTL.toMillis <= (30 days).toMillis, "maxTTL too long")
//  assert(minTTL.toMillis <= maxTTL.toMillis, "minTTL longer than maxTTL")

  // Constructor using a distinct serializer for each cache plugin
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innerMostPluginSettings: (FortyTwoCachePlugin, Duration, Duration, Serializer[T]),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration, Duration, Serializer[T])*) =
    this(stats, accessLog,
      innerMostPluginSettings._1, innerMostPluginSettings._2, innerMostPluginSettings._3, innerMostPluginSettings._4,
      FortyTwoCacheFactory[K, T](innerToOuterPluginSettings, stats, accessLog))

  // Constructor using the same serializer for each cache plugin
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(serializer: Serializer[T]) = {
    this(stats,
      accessLog,
      (innermostPluginSettings._1, innermostPluginSettings._2 * 0.9, innermostPluginSettings._2, serializer),
      innerToOuterPluginSettings.map { case (plugin, ttl) => (plugin, ttl * 0.9, ttl, serializer) }: _*)
  }

  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration, Duration)*)(serializer: Serializer[T]) = {
    this(stats,
      accessLog,
      innermostPluginSettings match { case (plugin, minTTL, maxTTL) => (plugin, minTTL, if (minTTL > maxTTL) minTTL else maxTTL, serializer) },
      innerToOuterPluginSettings.map { case (plugin, minTTL, maxTTL) => (plugin, minTTL, if (minTTL > maxTTL) minTTL else maxTTL, serializer) }: _*)
  }
}

