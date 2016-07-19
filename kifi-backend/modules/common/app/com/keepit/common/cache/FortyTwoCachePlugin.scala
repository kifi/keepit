package com.keepit.common.cache

import scala.concurrent._

import java.util.concurrent.TimeUnit
import java.util.{ Map => JMap }

import net.sf.ehcache._
import net.sf.ehcache.config.CacheConfiguration

import net.spy.memcached.{ CachedData, MemcachedClient }
import net.spy.memcached.transcoders.{ Transcoder, SerializingTranscoder }
import net.spy.memcached.internal.{ CheckedOperationTimeoutException, BulkFuture, GetFuture }

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }

import play.api.Logger
import play.api.Plugin
import play.api.libs.concurrent.Execution.Implicits._

import scala.collection.JavaConverters._

trait FortyTwoCachePlugin extends Plugin {
  private[cache] def onError(error: AirbrakeError) {}
  private[cache] val logAccess: Boolean = false

  def get(key: String): Option[Any]
  def remove(key: String): Unit
  def set(key: String, value: Any, expiration: Int = 0): Unit

  def bulkGet(keys: Set[String]): Map[String, Any] = {
    keys.foldLeft(Map.empty[String, Any]) { (m, k) =>
      get(k) match {
        case Some(value) => (m + (k -> value))
        case _ => m
      }
    }
  }

  override def enabled = true
  override def toString = "Cache"
}

trait InMemoryCachePlugin extends FortyTwoCachePlugin {
  def removeAll(prefix: Option[String]): Unit
}

@Singleton
class MemcachedCache @Inject() (
    clientProvider: MemcachedClientProvider,
    val airbrake: AirbrakeNotifier) extends FortyTwoCachePlugin {

  override private[cache] val logAccess = true

  override def onError(error: AirbrakeError) {
    airbrake.notify(error)
  }

  private def getClient: MemcachedClient = clientProvider.get()
  private def getBulkClient: MemcachedClient = clientProvider.getBulkClient()

  val compressThreshold: Int = 400000 // TODO: make configurable
  val compressMethod: String = "gzip"
  val maxThreshold: Int = 900000

  lazy val logger = Logger("memcached.plugin")
  import java.io._

  class CustomSerializing extends SerializingTranscoder {

    override def encode(o: Object): CachedData = { // SerializingTranscoder does not compress JSON
      o match {
        case s: String => {
          var b: Array[Byte] = encodeString(s)
          var flags: Int = 0
          if (b.length > compressThreshold) {
            val ts = System.currentTimeMillis
            val compressed = compressMethod match {
              case "gzip" => compress(b)
              case _ => throw new UnsupportedOperationException(s"Compression method $compressMethod not supported")
            }
            val lapsed = System.currentTimeMillis - ts
            if (compressed.length < b.length) {
              logger.info(s"Compressed($compressMethod) ${s.take(200)}: ${b.length} => ${compressed.length} in $lapsed ms")
              b = compressed
              flags = 2 // COMPRESSED (gzip) -- @see SerializingTranscoder
            } else {
              logger.warn(s"Compression($compressMethod) INCREASED size of ${s.take(200)}: ${b.length} => ${compressed.length} in $lapsed ms")
            }
          }
          if (b.length > maxThreshold) {
            throw new CacheSizeLimitExceededException(s"object $o size=${b.length} is greater than memcached max threshold=$maxThreshold")
          }
          new CachedData(flags, b, getMaxSize)
        }
        case _ => super.encode(o)
      }
    }

    // You should not catch exceptions and return nulls here,
    // because you should cancel the future returned by asyncGet() on any exception.
    override protected def deserialize(data: Array[Byte]): java.lang.Object = {
      new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(data)) {
        override protected def resolveClass(desc: ObjectStreamClass) = {
          Class.forName(desc.getName(), false, play.api.Play.current.classloader)
        }
      }.readObject()
    }

    // We don't catch exceptions here to make it corresponding to `deserialize`.
    override protected def serialize(obj: java.lang.Object) = {
      val bos: ByteArrayOutputStream = new ByteArrayOutputStream()
      // Allows serializing `null`.
      // See https://github.com/mumoshu/play2-memcached/issues/7
      new ObjectOutputStream(bos).writeObject(obj)
      bos.toByteArray()
    }
  }

  lazy val tc = new CustomSerializing().asInstanceOf[Transcoder[Any]]

  private def handleTimeoutException(client: MemcachedClient, bulk: Boolean = false): Unit = {
    try {
      if (bulk) {
        clientProvider.recreateBulk(client)
      } else {
        clientProvider.recreate(client)
      }
    } catch {
      case e: Exception => airbrake.notify(s"failed to recreate memcached client after CheckedOperationTimeoutException")
    }
  }

  def get(key: String) = {
    logger.debug("Getting the cached for key " + key)
    var future: GetFuture[Any] = null
    val client = getClient
    try {
      future = client.asyncGet(key, tc)
      toOption(future.get(1, TimeUnit.SECONDS))
    } catch {
      case timeout: CheckedOperationTimeoutException =>
        //airbrake.notify("A timeout error has occurred while getting the value from memcached", timeout)
        handleTimeoutException(client)
        if (future != null) future.cancel(false)
        None
      case e: Throwable =>
        logger.error("An error has occurred while getting the value from memcached", e)
        if (future != null) future.cancel(false)
        None
    }
  }

  def set(key: String, value: Any, expiration: Int): Unit = {
    val client = getClient
    try {
      client.set(key, expiration, value, tc)
    } catch {
      case timeout: CheckedOperationTimeoutException =>
        handleTimeoutException(client)
      case t: Throwable =>
        logger.error("An error has occurred while setting the value to memcached", t)
    }
  }

  def remove(key: String) {
    val client = getClient
    try {
      client.delete(key)
    } catch {
      case timeout: CheckedOperationTimeoutException =>
        handleTimeoutException(client)
      case t: Throwable =>
        logger.error("An error has occurred while deleting the value from memcached", t)
    }
  }

  // do not overload cache client with giant bulkget
  private def smallBulkGet(keys: Set[String]): Map[String, Any] = {
    logger.debug("Getting the cached for keys " + keys)
    var future: BulkFuture[JMap[String, Any]] = null
    val client = getBulkClient
    try {
      future = client.asyncGetBulk(keys.asJava, tc)
      future.getSome(1, TimeUnit.SECONDS).asScala.foldLeft(Map.empty[String, Any]) { (m, kv) =>
        toOption(kv._2) match {
          case Some(v) => m + (kv._1 -> v)
          case _ => m
        }
      }
    } catch {
      case timeout: CheckedOperationTimeoutException =>
        airbrake.notify(s"A timeout error has occurred while bulk getting ${keys.size} values from memcached", timeout)
        handleTimeoutException(client, bulk = true)
        if (future != null) future.cancel(false)
        Map.empty[String, Any]

      case e: Throwable =>
        logger.error("An error has occurred while getting some values from memcached", e)
        if (future != null) future.cancel(false)
        Map.empty[String, Any]
    }
  }

  override def bulkGet(keys: Set[String]): Map[String, Any] = {
    if (keys.size >= 1000) {
      keys.grouped(500).map { subkeys => smallBulkGet(subkeys) }.foldLeft(Map.empty[String, Any]) { case (m1, m2) => m1 ++ m2 }
    } else {
      smallBulkGet(keys)
    }
  }

  override def onStop(): Unit = {
    super.onStop()
    //shutting down the cache plugin will cause problems to mid flight requests
    //client.shutdown()
  }

  protected def toOption(any: Any): Option[Any] = {
    if (any != null) {
      logger.debug("any is " + any.getClass)
    }
    Option(
      any match {
        case x: java.lang.Byte => x.byteValue()
        case x: java.lang.Short => x.shortValue()
        case x: java.lang.Integer => x.intValue()
        case x: java.lang.Long => x.longValue()
        case x: java.lang.Float => x.floatValue()
        case x: java.lang.Double => x.doubleValue()
        case x: java.lang.Character => x.charValue()
        case x: java.lang.Boolean => x.booleanValue()
        case x => x
      }
    )
  }

  override def toString = MemcachedCache.name

}

object MemcachedCache {
  val name = "Memcached"
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
  override def onStart() { cache } //keep me alive!
  override def onStop() {
    super.onStop()
    //shutting down the cache plugin will cause problems to mid flight requests
    //manager.shutdown()
  }
  override def onError(error: AirbrakeError) { airbrake.notify(error) }

  def get(key: String): Option[Any] = Option(cache.get(key)).map(_.getObjectValue)
  def remove(key: String) { cache.remove(key) }

  def set(key: String, value: Any, expiration: Int = 0): Unit = Future {
    val element = new Element(key, value)
    if (expiration == 0) element.setEternal(true)
    element.setTimeToLive(expiration)
    cache.put(element)
  }

  override def toString = "EhCache"

  def removeAll(prefix: Option[String]): Unit = prefix.map(manager.clearAllStartingWith) getOrElse manager.clearAll()

}
