package com.keepit.common.cache

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import com.google.inject.{Inject, Singleton}
import scala.collection.concurrent.{TrieMap=>ConcurrentMap}
import play.api.libs.json._
import play.api.Plugin
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent._
import play.modules.statsd.api.Statsd
import com.keepit.common.time._
import com.keepit.serializer.{Serializer, BinaryFormat}

object CacheStatistics {
  private val hitsMap = ConcurrentMap[String, AtomicInteger]()
  private val missesMap = ConcurrentMap[String, AtomicInteger]()
  private val setsMap = ConcurrentMap[String, AtomicInteger]()

  private def incrCount(key: String, m: ConcurrentMap[String, AtomicInteger]) {
    m.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet()
  }
  private def getCount(key: String, m: ConcurrentMap[String, AtomicInteger]): Int = {
    m.get(key) match {
      case Some(counter) => counter.get()
      case _ => 0
    }
  }

  def recordHit(cachePlugin: FortyTwoCachePlugin, namespace: String, millis: Long) {
    incrCount(s"$cachePlugin.$namespace", hitsMap)
    Statsd.increment(s"$cachePlugin.$namespace.hits")
    Statsd.timing(s"$cachePlugin.$namespace.hits", millis)
  }
  def recordMiss(cachePlugin: FortyTwoCachePlugin, namespace: String) {
    incrCount(s"$cachePlugin.$namespace", missesMap)
    Statsd.increment(s"$cachePlugin.$namespace.misses")
  }

  def recordSet(cachePlugin: FortyTwoCachePlugin, namespace: String, millis: Long) {
    incrCount(s"$cachePlugin.$namespace", setsMap)
    Statsd.increment(s"$cachePlugin.$namespace.sets")
    Statsd.timing(s"$cachePlugin.$namespace.sets", millis)
  }

  def getStatistics: Seq[(String, Int, Int, Int)] = {
    val keys = (hitsMap.keySet ++ missesMap.keySet ++ setsMap.keySet).toSeq.sorted
    keys map { key =>
      (key, getCount(key, hitsMap), getCount(key, missesMap), getCount(key, setsMap))
    }
  }
}

// Abstraction around play2-memcached plugin
trait FortyTwoCachePlugin extends Plugin {
  private[cache] def onError(error: HealthcheckError) {}

  def get(key: String): Option[Any]
  def remove(key: String): Unit
  def set(key: String, value: Any, expiration: Int = 0): Unit

  override def enabled = true
}

trait InMemoryCachePlugin extends FortyTwoCachePlugin

@Singleton
class MemcachedCache @Inject() (
  val cache: MemcachedPlugin,
  val healthcheck: HealthcheckPlugin) extends FortyTwoCachePlugin {
  def get(key: String): Option[Any] =
    cache.api.get(key)

  override def onError(he: HealthcheckError) {
    healthcheck.addError(he)
  }

  def remove(key: String) {
    cache.api.remove(key)
  }

  def set(key: String, value: Any, expiration: Int = 0) {
    cache.api.set(key, value, expiration)
  }

  override def onStop() {
    cache.onStop()
  }

  override def toString = "Memcached"
}

@Singleton
class EhCacheCache @Inject() (val healthcheck: HealthcheckPlugin) extends InMemoryCachePlugin {

  import play.api.Play
  import play.api.cache.{EhCachePlugin, Cache}

  override def onError(he: HealthcheckError) {
    healthcheck.addError(he)
  }

  def get(key: String): Option[Any] =
    Play.current.plugin[EhCachePlugin].map { ehcache =>
      ehcache.api.get(key)
    } flatten

  def remove(key: String) {
    Play.current.plugin[EhCachePlugin].map {
      ehcache =>
        ehcache.api.remove(key)
    }
  }

  def set(key: String, value: Any, expiration: Int = 0) {
    Play.current.plugin[EhCachePlugin].map { ehcache =>
      ehcache.api.set(key, value, expiration)
    }
  }

  override def toString = "EhCache"

}

@Singleton
class HashMapMemoryCache extends InMemoryCachePlugin {

  val cache = ConcurrentMap[String, Any]()

  def get(key: String): Option[Any] =
    cache.get(key)

  def remove(key: String) {
    cache.remove(key)
  }

  def set(key: String, value: Any, expiration: Int = 0) {
    cache += key -> value
  }

  override def toString = "HashMapMemoryCache"

}

@Singleton
class NoOpCache extends FortyTwoCachePlugin {

  def get(key: String): Option[Any] = None

  def remove(key: String) {}

  def set(key: String, value: Any, expiration: Int = 0) {}

  override def toString = "NoOpCache"

}

trait Key[T] {
  val namespace: String
  val version: Int = 1
  def toKey(): String
  override final def toString: String = namespace + "#" + version + "#" + toKey()
}

trait ObjectCache[K <: Key[T], T] {
  val outerCache: Option[ObjectCache[K, T]] = None
  val ttl: Duration
  outerCache map {outer => require(ttl <= outer.ttl)}

  protected[cache] def getFromInnerCache(key: K): Option[T]
  protected[cache] def setInnerCache(key: K, value: T): Future[T]

  def remove(key: K): Unit

  def set(key: K, value: T): Future[T] = {
    outerCache map {outer => outer.set(key, value)}
    setInnerCache(key, value)
  }

  def get(key: K): Option[T] = getOrElseOpt(key)(None)

  def getOrElse(key: K)(orElse: => T): T = {
    getFromInnerCache(key).getOrElse {
      val value = outerCache match {
        case Some(cache) => cache.getOrElse(key)(orElse)
        case None => orElse
      }
      setInnerCache(key, value)
      value
    }
  }

  def getOrElseOpt(key: K)(orElse: => Option[T]): Option[T] = {
    getFromInnerCache(key) match {
      case s @ Some(value) => s
      case None =>
        val valueOption = outerCache match {
          case Some(cache) => cache.getOrElseOpt(key)(orElse)
          case None => orElse
        }
        valueOption.map {value => setInnerCache(key, value)}
        valueOption
    }
  }

  def getOrElseFuture(key: K)(orElse: => Future[T]): Future[T] = {
    getFromInnerCache(key) match {
      case Some(value) => Promise.successful(value).future
      case None =>
        val valueFuture = outerCache match {
          case Some(cache) => cache.getOrElseFuture(key)(orElse)
          case None => orElse
        }
        valueFuture.onSuccess {case value => setInnerCache(key, value)}
        valueFuture
    }
  }

  def getOrElseFutureOpt(key: K)(orElse: => Future[Option[T]]): Future[Option[T]] = {
    getFromInnerCache(key) match {
      case s @ Some(value) => Promise.successful(s).future
      case None =>
        val valueFutureOption = outerCache match {
          case Some(cache) => cache.getOrElseFutureOpt(key)(orElse)
          case None => orElse
        }
        valueFutureOption.onSuccess {case valueOption => valueOption.map {value => setInnerCache(key, value)}}
        valueFutureOption
    }
  }
}

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] {
  val repo: FortyTwoCachePlugin
  val serializer: Serializer[T]
  val stats = CacheStatistics

  protected[cache] def getFromInnerCache(key: K): Option[T] = {
    val getStart = currentDateTime.getMillis()
    val value = try repo.get(key.toString) catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed fetching key $key from $repo")))
        None
    }
    try {
      val objOpt = value.map(serializer.reads)
      val getEnd = currentDateTime.getMillis()
      objOpt match {
        case Some(_) => CacheStatistics.recordHit(repo, key.namespace, getEnd - getStart)
        case None => CacheStatistics.recordMiss(repo, key.namespace)
      }
      objOpt
    } catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed deserializing key $key from $repo, got raw value $value")))
        repo.remove(key.toString)
        None
    }
  }

  protected[cache] def setInnerCache(key: K, value: T): Future[T] = {
    val setStart = currentDateTime.getMillis()
    future {
      val properlyBoxed = serializer.writes(value) match {
        case x: java.lang.Byte => x.byteValue()
        case x: java.lang.Short => x.shortValue()
        case x: java.lang.Integer => x.intValue()
        case x: java.lang.Long => x.longValue()
        case x: java.lang.Float => x.floatValue()
        case x: java.lang.Double => x.doubleValue()
        case x: java.lang.Character => x.charValue()
        case x: java.lang.Boolean => x.booleanValue()
        case x: scala.Array[_] => x
        case x: JsValue => Json.stringify(x)
        case x: String => x
      }
      repo.set(key.toString, properlyBoxed, ttl.toSeconds.toInt)
      val setEnd = currentDateTime.getMillis()
      CacheStatistics.recordSet(repo, key.namespace, setEnd - setStart)
      value
    } recover {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed setting key $key in $repo")))
        throw e
    }
  }

  def remove(key: K) {
    repo.remove(key.toString)
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

class JsonCacheImpl[K <: Key[T], T](innermostPlugin: (FortyTwoCachePlugin, Duration), innerToOuterPlugins: (FortyTwoCachePlugin, Duration)*)(implicit formatter: Format[T])
  extends FortyTwoCacheImpl[K, T](innermostPlugin, innerToOuterPlugins:_*)(Serializer(formatter))

class BinaryCacheImpl[K <: Key[T], T](innermostPlugin: (FortyTwoCachePlugin, Duration), innerToOuterPlugins: (FortyTwoCachePlugin, Duration)*)(implicit formatter: BinaryFormat[T])
  extends FortyTwoCacheImpl[K, T](innermostPlugin, innerToOuterPlugins:_*)(Serializer(formatter))

class PrimitiveCacheImpl[K <: Key[P], P <: AnyVal](innermostPlugin: (FortyTwoCachePlugin, Duration), innerToOuterPlugins: (FortyTwoCachePlugin, Duration)*)
  extends FortyTwoCacheImpl[K, P](innermostPlugin, innerToOuterPlugins:_*)(Serializer[P])

class StringCacheImpl[K <: Key[String]](innermostPlugin: (FortyTwoCachePlugin, Duration), innerToOuterPlugins: (FortyTwoCachePlugin, Duration)*)
  extends FortyTwoCacheImpl[K, String](innermostPlugin, innerToOuterPlugins:_*)(Serializer.string)

