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
import com.keepit.common.logging.{AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access._

import play.api.Logger
import play.api.Plugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.modules.statsd.api.Statsd

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
