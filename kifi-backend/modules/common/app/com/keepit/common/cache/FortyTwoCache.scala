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
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.serializer.{Serializer, BinaryFormat}

import play.api.Logger
import play.api.Plugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.modules.statsd.api.Statsd

//hate having an object here, should refactor to @Singleton
object GlobalCacheStatistics {
  private[cache] val hitsMap = ConcurrentMap[String, AtomicInteger]()
  private[cache] val missesMap = ConcurrentMap[String, AtomicInteger]()
  private[cache] val setsMap = ConcurrentMap[String, AtomicInteger]()

  def getStatistics: Seq[(String, Int, Int, Int)] = {
    val keys = (hitsMap.keySet ++ missesMap.keySet ++ setsMap.keySet).toSeq.sorted
    keys map { key =>
      (key, getCount(key, hitsMap), getCount(key, missesMap), getCount(key, setsMap))
    }
  }

  private[cache] def getCount(key: String, m: ConcurrentMap[String, AtomicInteger]): Int = {
    m.get(key) match {
      case Some(counter) => counter.get()
      case _ => 0
    }
  }
}

trait CacheStatistics extends Logging {
  val global = GlobalCacheStatistics
  private def incrCount(key: String, m: ConcurrentMap[String, AtomicInteger]) {
    m.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet()
  }
  private val accessLog = Logger("com.keepit.access")

  def recordHit(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, millis: Long) {
    val name = s"$cachePlugin.$namespace"
    incrCount(name, global.hitsMap)
    Statsd.increment(s"$name.hits")
    Statsd.timing(s"$name.hits", millis)
    if (logAccess) accessLog.info(s"""[CACHE] [$cachePlugin] HIT  $fullKey took [${millis}ms]""")
  }

  def recordMiss(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, millis: Long) {
    val name = s"$cachePlugin.$namespace"
    incrCount(s"$name", global.missesMap)
    Statsd.increment(s"$name.misses")
    log.warn(s"Cache miss on key $fullKey in $cachePlugin")
    if (logAccess) accessLog.info(s"""[CACHE] [$cachePlugin] MISS $fullKey took [${millis}ms]""")
  }

  def recordSet(cachePlugin: String, logAccess: Boolean, namespace: String, fullKey: String, millis: Long) {
    val name = s"$cachePlugin.$namespace"
    incrCount(s"$name", global.setsMap)
    Statsd.increment(s"$name.sets")
    Statsd.timing(s"$name.sets", millis)
    if (logAccess) accessLog.info(s"""[CACHE] [$cachePlugin] SET  $fullKey took [${millis}ms]""")
  }
}

trait FortyTwoCachePlugin extends Plugin {
  private[cache] def onError(error: AirbrakeError) {}

  private[cache] val logAccess: Boolean = false

  def get(key: String): Option[Any]
  def remove(key: String): Unit
  def set(key: String, value: Any, expiration: Int = 0): Unit

  override def enabled = true

  override def toString = "Cache"
}

trait InMemoryCachePlugin extends FortyTwoCachePlugin

@Singleton
class MemcachedCache @Inject() (
  val cache: MemcachedPlugin,
  val airbrake: AirbrakeNotifier) extends FortyTwoCachePlugin {

  override private[cache] val logAccess = true

  def get(key: String): Option[Any] = cache.api.get(key)

  override def onError(error: AirbrakeError) {
    airbrake.notify(error)
  }

  def remove(key: String) = cache.api.remove(key)

  def set(key: String, value: Any, expiration: Int = 0): Unit = future {
    cache.api.set(key, value, expiration)
  }

  override def onStop() = cache.onStop()

  override def toString = "Memcached"
}

class EhCacheConfiguration extends CacheConfiguration

@Singleton
class EhCacheCache @Inject() (
  config: EhCacheConfiguration,
  val airbrake: AirbrakeNotifier)
    extends InMemoryCachePlugin {

  lazy val (manager, cache) = {
    val manager = CacheManager.create()
    val cache = new Cache(config)
    manager.addCache(cache)
    (manager, cache)
  }
  override def onStart() { cache }
  override def onStop() { manager. shutdown() }
  override def onError(error: AirbrakeError) { airbrake.notify(error) }

  def get(key: String): Option[Any] = Option(cache.get(key)).map(_.getObjectValue)
  def remove(key: String) { cache.remove(key) }

  def set(key: String, value: Any, expiration: Int = 0): Unit = future {
    val element = new Element(key, value)
    if (expiration == 0) element.setEternal(true)
    element.setTimeToLive(expiration)
    cache.put(element)
  }

  override def toString = "EhCache"

}

trait Key[T] {
  val namespace: String
  val version: Int = 1
  def toKey(): String
  override final def toString: String = namespace + "%" + version + "#" + toKey()
}

case class CacheSizeLimitExceededException(msg:String) extends Exception(msg)

trait ObjectCache[K <: Key[T], T] {
  val outerCache: Option[ObjectCache[K, T]] = None
  val ttl: Duration
  outerCache map {outer => require(ttl <= outer.ttl)}

  protected[cache] def getFromInnerCache(key: K): Option[Option[T]]
  protected[cache] def setInnerCache(key: K, value: Option[T]): Unit

  def remove(key: K): Unit

  def set(key: K, value: T): Unit = {
    outerCache map {outer => outer.set(key, value)}
    setInnerCache(key, Some(value))
  }

  def set(key: K, valueOpt: Option[T]) : Unit = {
    outerCache map {outer => outer.set(key, valueOpt)}
    setInnerCache(key, valueOpt)
  }

  def get(key: K): Option[T] = {
    getFromInnerCache(key) match {
      case Some(valueOpt) => valueOpt
      case None => outerCache match {
        case Some(cache) => cache.get(key)
        case None => None
      }
    }
  }

  def getOrElse(key: K)(orElse: => T): T = {
    def fallback : T = {
      val value = outerCache match {
        case Some(cache) => cache.getOrElse(key)(orElse)
        case None => orElse
      }
      setInnerCache(key, Some(value))
      value
    }

    getFromInnerCache(key) match {
      case Some(valueOpt) => valueOpt match {
        case Some(value) => value
        case None => fallback
      }
      case None => fallback

    }
  }

  def getOrElseOpt(key: K)(orElse: => Option[T]): Option[T] = {
    getFromInnerCache(key) match {
      case Some(valueOpt) => valueOpt
      case None =>
        val valueOption : Option[T] = outerCache match {
          case Some(cache) => cache.getOrElseOpt(key)(orElse)
          case None => orElse
        }
        setInnerCache(key, valueOption)
        valueOption
    }
  }

  def getOrElseFuture(key: K)(orElse: => Future[T]): Future[T] = {
    def fallback: Future[T] = {
      val valueFuture = outerCache match {
        case Some(cache) => cache.getOrElseFuture(key)(orElse)
        case None => orElse
      }
      valueFuture.onSuccess {case value => setInnerCache(key, Some(value))}
      valueFuture
    }

    getFromInnerCache(key) match {
      case Some(valueOpt) => valueOpt match {
        case Some(value) => Promise.successful(value).future
        case None => fallback
      }
      case None => fallback
    }
  }

  def getOrElseFutureOpt(key: K)(orElse: => Future[Option[T]]): Future[Option[T]] = {
    getFromInnerCache(key) match {
      case Some(valueOpt) => Promise.successful(valueOpt).future
      case None =>
        val valueFutureOption = outerCache match {
          case Some(cache) => cache.getOrElseFutureOpt(key)(orElse)
          case None => orElse
        }
        valueFutureOption.onSuccess {case valueOption => setInnerCache(key, valueOption)}
        valueFutureOption
    }
  }
}

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] with CacheStatistics {
  val repo: FortyTwoCachePlugin
  val serializer: Serializer[T]

  protected[cache] def getFromInnerCache(key: K): Option[Option[T]] = {
    val getStart = currentDateTime.getMillis()
    val valueOpt = try repo.get(key.toString) catch {
      case e: Throwable =>
        repo.onError(AirbrakeError(e, Some(s"Failed fetching key $key from $repo")))
        None
    }
    try {
      val objOpt = valueOpt.map(serializer.reads)
      val time = currentDateTime.getMillis() - getStart
      objOpt match {
        case Some(_) => {
          recordHit(repo.toString, repo.logAccess, key.namespace, key.toString, time)
          recordHit("Cache", repo.logAccess, key.namespace, key.toString, time)
        }
        case None => {
          recordMiss(repo.toString, repo.logAccess, key.namespace, key.toString, time)
          if (outerCache isEmpty) recordMiss("Cache", repo.logAccess, key.namespace, key.toString, time)
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
      if (outerCache isEmpty) recordSet("Cache", repo.logAccess, key.namespace, key.toString, setEnd - setStart)
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
  def this(innerMostPluginSettings: (FortyTwoCachePlugin, Duration, Serializer[T]), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration, Serializer[T])*) =
    this(innerMostPluginSettings._1, innerMostPluginSettings._2, innerMostPluginSettings._3, FortyTwoCacheFactory[K, T](innerToOuterPluginSettings))

  // Constructor using the same serializer for each cache plugin
  def this(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(serializer: Serializer[T]) =
    this((innermostPluginSettings._1, innermostPluginSettings._2, serializer), innerToOuterPluginSettings.map {case (plugin, ttl) => (plugin, ttl, serializer)}:_*)
}

class JsonCacheImpl[K <: Key[T], T](innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: Format[T])
  extends FortyTwoCacheImpl[K, T](innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer(formatter))

class BinaryCacheImpl[K <: Key[T], T](innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: BinaryFormat[T])
  extends FortyTwoCacheImpl[K, T](innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer(formatter))

class PrimitiveCacheImpl[K <: Key[P], P <: AnyVal](innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends FortyTwoCacheImpl[K, P](innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer[P])

class StringCacheImpl[K <: Key[String]](innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends FortyTwoCacheImpl[K, String](innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer.string)
