package com.keepit.common.cache

import akka.util.Duration
import com.google.inject.{Inject, Singleton}
import com.keepit.inject._
import play.api.Play.current
import play.api.Plugin
import scala.collection.mutable

@Singleton
class CacheStatistics {
  private val hitsMap = mutable.HashMap[String, Int]()
  private val missesMap = mutable.HashMap[String, Int]()
  private val setsMap = mutable.HashMap[String, Int]()

  def incrHits(className: String) { hitsMap += className -> (hitsMap.getOrElse(className, 0) + 1) }
  def incrMisses(className: String) { missesMap += className -> (missesMap.getOrElse(className, 0) + 1) }
  def incrSets(className: String) { setsMap += className -> (setsMap.getOrElse(className, 0) + 1) }

  def getStatistics: Seq[(String, Int, Int, Int)] = {
    val keys = (hitsMap.keySet ++ missesMap.keySet ++ setsMap.keySet).toSeq.sorted
    keys map { key =>
      (key, hitsMap.getOrElse(key, 0), missesMap.getOrElse(key, 0), setsMap.getOrElse(key, 0))
    }
  }
}

// Abstraction around play2-memcached plugin
trait FortyTwoCachePlugin extends Plugin {
  def get(key: String): Option[Any]
  def remove(key: String): Unit
  def set(key: String, value: Any, expiration: Int = 0): Unit

  override def enabled = true
}

@Singleton
class MemcachedCache @Inject() (val cache: MemcachedPlugin) extends FortyTwoCachePlugin {
  def get(key: String): Option[Any] =
    cache.api.get(key)

  def remove(key: String) {
    cache.api.remove(key) // Play 2.0 does not support remove. 2.1 does!
  }

  def set(key: String, value: Any, expiration: Int = 0) {
    cache.api.set(key, value, expiration)
  }

  override def onStop() {
    cache.onStop()
  }
}

@Singleton
class InMemoryCache extends FortyTwoCachePlugin {

  import play.api.Play
  import play.api.cache.{EhCachePlugin, Cache}

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
class HashMapMemoryCache extends FortyTwoCachePlugin {

  val cache = mutable.HashMap[String, Any]()

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
  def toKey(): String
  override final def toString: String = namespace + "#" + toKey()
}

trait ObjectCache[K <: Key[T], T] {
  val ttl: Duration
  def serialize(value: T): Any
  def deserialize(obj: Any): T

  def get(key: K): Option[T]
  def set(key: K, value: T): Unit
  def remove(key: K): Unit

  def getOrElse(key: K)(orElse: => T): T = {
    get(key).getOrElse {
      val value = orElse
      set(key, value)
      value
    }
  }

  def getOrElseOpt(key: K)(orElse: => Option[T]): Option[T] = {
    get(key) match {
      case s @ Some(value) => s
      case None =>
        val value = orElse
        if (value.isDefined)
          set(key, value.get)
        value
    }
  }
}

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] {
  val repo: FortyTwoCachePlugin
  def get(key: K): Option[T] = {
    val objOpt = repo.get(key.toString).map(deserialize)
    objOpt match {
      case Some(_) => inject[CacheStatistics].incrHits(key.getClass.getSimpleName)
      case None => inject[CacheStatistics].incrMisses(key.getClass.getSimpleName)
    }
    objOpt
  }
  def set(key: K, value: T) {
    repo.set(key.toString, serialize(value), ttl.toSeconds.toInt)
    inject[CacheStatistics].incrSets(key.getClass.getSimpleName)
  }
  def remove(key: K) { repo.remove(key.toString) }
}
