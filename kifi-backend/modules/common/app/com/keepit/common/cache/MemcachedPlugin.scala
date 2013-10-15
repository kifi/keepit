package com.keepit.common.cache

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.{Map=>JMap}
import net.spy.memcached.auth.{PlainCallbackHandler, AuthDescriptor}
import net.spy.memcached.{CachedData, ConnectionFactoryBuilder, AddrUtil, MemcachedClient}
import net.spy.memcached.transcoders.{Transcoder, SerializingTranscoder}
import net.spy.memcached.compat.log.{Level, AbstractLogger}
import net.spy.memcached.internal.BulkFuture
import net.spy.memcached.internal.GetFuture
import play.api.cache.{CacheAPI, CachePlugin}
import play.api.{Logger, Play, Application}
import play.api.Play.current
import scala.util.control.Exception._
import scala.collection.JavaConverters._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.logging.Logging

class MemcachedSlf4JLogger(name: String) extends AbstractLogger(name) {

  val logger = Logger("memcached")

  def isDebugEnabled = logger.isDebugEnabled

  def isInfoEnabled = logger.isInfoEnabled

  def log(level: Level, msg: AnyRef, throwable: Throwable) {
    val message = msg.toString
    level match {
      case Level.DEBUG => logger.debug(message, throwable)
      case Level.INFO => logger.info(message, throwable)
      case Level.WARN => logger.warn(message, throwable)
      case Level.ERROR => logger.error(message, throwable)
      case Level.FATAL => logger.error("[FATAL] " + message, throwable)
    }
  }
}

@Singleton
class MemcachedPlugin @Inject() (client: MemcachedClient) extends CachePlugin {

  val compressThreshold:Int = 400000 // TODO: make configurable
  val compressMethod:String = "gzip"
  val maxThreshold:Int = 900000

  lazy val logger = Logger("memcached.plugin")
  import java.io._

  class CustomSerializing extends SerializingTranscoder{

    override def encode(o:Object):CachedData = { // SerializingTranscoder does not compress JSON
      o match {
        case s:String => {
          var b:Array[Byte] = encodeString(s)
          var flags:Int = 0
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

  lazy val api = new CacheAPI {

    def get(key: String) = {
      logger.debug("Getting the cached for key " + key)
      var future: GetFuture[Any] = null
      try {
        future = client.asyncGet(key, tc)
        toOption(future.get(1, TimeUnit.SECONDS))
      } catch {
        case e: Throwable =>
          logger.error("An error has occurred while getting the value from memcached" , e)
          if (future != null) future.cancel(false)
          None
      }
    }

    def set(key: String, value: Any, expiration: Int) {
      client.set(key, expiration, value, tc)
    }

    def remove(key: String) {
      client.delete(key)
    }
  }

  override def onStop(): Unit = {
    client.shutdown()
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

  def bulkGet(keys: Set[String]): Map[String, Any] = {
    logger.debug("Getting the cached for keys " + keys)
    var future: BulkFuture[JMap[String, Any]] = null
    try {
      future = client.asyncGetBulk(keys.asJava, tc)
      future.getSome(1, TimeUnit.SECONDS).asScala.foldLeft(Map.empty[String, Any]){ (m, kv) =>
        toOption(kv._2) match {
          case Some(v) => m + (kv._1 -> v)
          case _ => m
        }
      }
    } catch {
      case e: Throwable =>
        logger.error("An error has occurred while getting some values from memcached" , e)
        if (future != null) future.cancel(false)
        Map.empty[String, Any]
    }
  }
}
