package com.keepit.common.cache

import play.api.cache._
import collection.mutable
import play.api.Play.current
import scala.reflect.ClassManifest
import com.github.mumoshu.play2.memcached.MemcachedPlugin
import play.api.Plugin
import akka.util.Duration
import akka.util.duration._
import play.api.libs.json.JsValue

// Abstraction around play2-memcached plugin
trait FortyTwoCachePlugin extends Plugin {
  def get(key: String): Option[Any]
  def getOrElse[A](key: String, expiration: Int)(orElse: => A)(implicit m: ClassManifest[A]): A // as per the Play! 2.x api, this side effects and also sets the key
  def getAs[T](key: String)(implicit m: ClassManifest[T]): Option[T] // @deprecated in Scala 2.10, todo(Andrew): Upgrade to Class Tag
  def remove(key: String): Unit
  def set(key: String, value: Any, expiration: Int = 0): Unit
  def getOrElse[A](expiration: Int)(key: Any*)(orElse: => A)(implicit m: ClassManifest[A]): A =
    getOrElse[A](key.map(_.toString.replaceAll("\u2028","")).mkString("\u2028"), expiration)(orElse)
}

class MemcachedCache extends FortyTwoCachePlugin {
  def get(key: String): Option[Any] =
    Cache.get(key)

  def getOrElse[T](key: String, expiration: Int = 0)(orElse: => T)(implicit m: ClassManifest[T]): T =
    getAs[T](key).getOrElse {
      val value = orElse
      set(key, value, expiration)
      value
    }

  def getAs[T](key: String)(implicit m: ClassManifest[T]): Option[T] =
    Cache.getAs[T](key)

  def remove(key: String): Unit =
    play.api.Play.current.plugin[MemcachedPlugin].get.api.remove(key) // Play 2.0 does not support remove. 2.1 does!

  def set(key: String, value: Any, expiration: Int = 0): Unit =
    Cache.set(key, value, expiration)
}

class InMemoryCache extends FortyTwoCachePlugin {
  private[this] val cache = new mutable.HashMap[String, Any]()

  def get(key: String): Option[Any] =
    cache.get(key)

  def getOrElse[A](key: String, expiration: Int = 0)(orElse: => A)(implicit m: ClassManifest[A]): A =
    getAs[A](key).getOrElse {
      val value = orElse
      set(key, value, expiration)
      value
    }

  def getAs[T](key: String)(implicit m: ClassManifest[T]): Option[T] =
    get(key).map(_.asInstanceOf[T])

  def remove(key: String): Unit =
    cache.remove(key)

  def set(key: String, value: Any, expiration: Int = 0): Unit =
    cache += ((key,value))
}

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._

trait Key[T] {
  val namespace: String
  def toKey(): String
  override final def toString: String = namespace + ":" + toKey()
}

trait ObjectCache[K <: Key[T], T] {
  val ttl: Duration
  def serialize(value: T): Any
  def deserialize(obj: Any): T

  def get(key: K): Option[T]
  def set(key: K, value: T): Unit

  def getOrElse(key: K)(orElse: => T): T = {
    get(key).getOrElse {
      val value = orElse
      set(key, value)
      value
    }
  }
}

trait FortyTwoCache[K <: Key[T], T] extends ObjectCache[K, T] {
  val repo = inject[FortyTwoCachePlugin]
  def get(key: K): Option[T] = repo.get(key.toString).map(deserialize)
  def set(key: K, value: T): Unit = repo.set(key.toString, serialize(value))
}



