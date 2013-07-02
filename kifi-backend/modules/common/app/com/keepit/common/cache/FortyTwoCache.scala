package com.keepit.common.cache

import scala.collection.concurrent.{TrieMap => ConcurrentMap}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import com.google.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.Plugin
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent._
import play.modules.statsd.api.Statsd
import com.keepit.common.time._
import com.keepit.serializer.{Serializer, BinaryFormat}
import net.sf.ehcache._
import net.sf.ehcache.config.CacheConfiguration
import net.codingwell.scalaguice.ScalaModule


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

  def recordHit(cachePlugin: String, namespace: String, millis: Long) {
    incrCount(s"$cachePlugin.$namespace", hitsMap)
    Statsd.increment(s"$cachePlugin.$namespace.hits")
    Statsd.timing(s"$cachePlugin.$namespace.hits", millis)
  }
  def recordMiss(cachePlugin: String, namespace: String) {
    incrCount(s"$cachePlugin.$namespace", missesMap)
    Statsd.increment(s"$cachePlugin.$namespace.misses")
  }

  def recordSet(cachePlugin: String, namespace: String, millis: Long) {
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

  def set(key: String, value: Any, expiration: Int = 0): Unit = future {
    cache.api.set(key, value, expiration)
  }

  override def onStop() {
    cache.onStop()
  }

  override def toString = "Memcached"
}

class EhCacheConfiguration extends CacheConfiguration

@Singleton
class EhCacheCache @Inject() (config: EhCacheConfiguration, val healthcheck: HealthcheckPlugin) extends InMemoryCachePlugin {
  lazy val (manager, cache) = {
    val manager = CacheManager.create()
    val cache = new Cache(config)
    manager.addCache(cache)
    (manager, cache)
  }
  override def onStart() { cache }
  override def onStop() { manager. shutdown() }
  override def onError(he: HealthcheckError) { healthcheck.addError(he) }

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
  override final def toString: String = namespace + "#" + version + "#" + toKey()
}

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

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] {
  val repo: FortyTwoCachePlugin
  val serializer: Serializer[T]
  val stats = CacheStatistics


  protected[cache] def getFromInnerCache(key: K): Option[Option[T]] = {
    val getStart = currentDateTime.getMillis()
    val valueOpt = try repo.get(key.toString) catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed fetching key $key from $repo")))
        None
    }
    try {
      val objOpt = valueOpt.map(serializer.reads)
      val getEnd = currentDateTime.getMillis()
      objOpt match {
        case Some(_) => {
          CacheStatistics.recordHit(repo.toString, key.namespace, getEnd - getStart)
          CacheStatistics.recordHit("Cache", key.namespace, getEnd - getStart)
        }
        case None => {
          CacheStatistics.recordMiss(repo.toString, key.namespace)
          if (outerCache isEmpty) CacheStatistics.recordMiss("Cache", key.namespace)
        }
      }
      objOpt
    } catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed deserializing key $key from $repo, got raw value $valueOpt")))
        repo.remove(key.toString)
        None
    }
  }

  protected[cache] def setInnerCache(key: K, valueOpt: Option[T]): Unit = {
    val setStart = currentDateTime.getMillis()
    try {
      val properlyBoxed = serializer.writes(valueOpt) match {
            case (flag: Boolean, x: java.lang.Byte) => (flag, x.byteValue())
            case (flag: Boolean, x: java.lang.Short) => (flag, x.shortValue())
            case (flag: Boolean, x: java.lang.Integer) => (flag, x.intValue())
            case (flag: Boolean, x: java.lang.Long) => (flag, x.longValue())
            case (flag: Boolean, x: java.lang.Float) => (flag, x.floatValue())
            case (flag: Boolean, x: java.lang.Double) => (flag, x.doubleValue())
            case (flag: Boolean, x: java.lang.Character) => (flag, x.charValue())
            case (flag: Boolean, x: java.lang.Boolean) => (flag, x.booleanValue())
            case (false, _) => (false, null) 
            case x: scala.Array[_] => x
            case x: JsValue => Json.stringify(x)
            case x: String => x
          }
      repo.set(key.toString, properlyBoxed, ttl.toSeconds.toInt)
      val setEnd = currentDateTime.getMillis()
      CacheStatistics.recordSet(repo.toString, key.namespace, setEnd - setStart)
      if (outerCache isEmpty) CacheStatistics.recordSet("Cache", key.namespace, setEnd - setStart)
    } catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed setting key $key in $repo")))
        throw e
    }
  }

  def remove(key: K) {
    try repo.remove(key.toString) catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed removing key $key from $repo")))
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
