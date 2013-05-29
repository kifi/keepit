package com.keepit.common.cache

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import com.google.inject.{Inject, Singleton}
import play.api.Play.current
import scala.collection.concurrent.{TrieMap=>ConcurrentMap}
import play.api.libs.json._
import play.api.Plugin
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class CacheStatistics {
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

  def incrHits(className: String) { incrCount(className, hitsMap) }
  def incrMisses(className: String) { incrCount(className, missesMap) }
  def incrSets(className: String) { incrCount(className, setsMap) }

  def getStatistics: Seq[(String, Int, Int, Int)] = {
    val keys = (hitsMap.keySet ++ missesMap.keySet ++ setsMap.keySet).toSeq.sorted
    keys map { key =>
      (key, getCount(key, hitsMap), getCount(key, missesMap), getCount(key, setsMap))
    }
  }
}

// Abstraction around play2-memcached plugin
trait FortyTwoCachePlugin extends Plugin {
  val stats: CacheStatistics

  private[cache] def onError(error: HealthcheckError) {}

  def get(key: String): Option[Any]
  def remove(key: String): Unit
  def set(key: String, value: Any, expiration: Int = 0): Unit

  override def enabled = true
}

@Singleton
class MemcachedCache @Inject() (
  val cache: MemcachedPlugin,
  val stats: CacheStatistics,
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
}

@Singleton
class InMemoryCache @Inject() (
  val stats: CacheStatistics,
  val healthcheck: HealthcheckPlugin) extends FortyTwoCachePlugin {

  import play.api.Play
  import play.api.cache.{EhCachePlugin, Cache}

  override def onError(he: HealthcheckError) {
    healthcheck.addError(he)
  }

  def get(key: String): Option[Any] =
    Cache.get(key)

  def remove(key: String) {
    Play.current.plugin[EhCachePlugin].map {
      ehcache =>
        ehcache.cache.remove(key)
    }
  }

  def set(key: String, value: Any, expiration: Int = 0) {
    Cache.set(key, value, expiration)
  }
}

@Singleton
class HashMapMemoryCache @Inject() (
  val stats: CacheStatistics) extends FortyTwoCachePlugin {

  val cache = ConcurrentMap[String, Any]()

  def get(key: String): Option[Any] =
    cache.get(key)

  def remove(key: String) {
    cache.remove(key)
  }

  def set(key: String, value: Any, expiration: Int = 0) {
    cache += key -> value
  }
}


trait Key[T] {
  val namespace: String
  val version: Int = 1
  def toKey(): String
  override final def toString: String = namespace + "#" + version + "#" + toKey()
}

trait ObjectCache[K <: Key[T], T] {
  val ttl: Duration
  def serialize(value: T): Any
  def deserialize(obj: Any): T

  def get(key: K): Option[T]
  def set(key: K, value: T): T
  def remove(key: K): Unit

  def getOrElse(key: K)(orElse: => T): T = {
    get(key).getOrElse {
      val value = orElse
      set(key, value)
    }
  }

  def getOrElseOpt(key: K)(orElse: => Option[T]): Option[T] = {
    get(key) match {
      case s @ Some(value) => s
      case None =>
        val value = orElse
        if (value.isDefined)
          Some(set(key, value.get))
        else
          value
    }
  }
}

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] {
  val repo: FortyTwoCachePlugin
  def get(key: K): Option[T] = {
    val value = try repo.get(key.toString) catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed fetching key $key from cache")))
        None
    }
    try {
      val objOpt = value.map(deserialize)
      objOpt match {
        case Some(_) => repo.stats.incrHits(key.getClass.getSimpleName)
        case None => repo.stats.incrMisses(key.getClass.getSimpleName)
      }
      objOpt
    } catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed deserializing key $key from cache, got raw value $value")))
        remove(key)
        None
    }
  }

  def set(key: K, value: T): T =
    try {
      val properlyBoxed = serialize(value) match {
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
      repo.stats.incrSets(key.getClass.getSimpleName)
      value
    } catch {
      case e: Throwable =>
        repo.onError(HealthcheckError(Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Failed setting key $key in cache")))
        value
    }

  def remove(key: K) { repo.remove(key.toString) }

  def parseJson(obj: Any)(implicit formatter: Format[T]): T =
    Json.fromJson[T](Json.parse(obj.asInstanceOf[String]).asInstanceOf[JsValue]).get
}
