package com.keepit.common.cache

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import net.spy.memcached.auth.{PlainCallbackHandler, AuthDescriptor}
import net.spy.memcached.{ConnectionFactoryBuilder, AddrUtil, MemcachedClient}
import play.api.cache.{CacheAPI, CachePlugin}
import play.api.{Logger, Play, Application}
import scala.util.control.Exception._
import net.spy.memcached.transcoders.{Transcoder, SerializingTranscoder}
import net.spy.memcached.compat.log.{Level, AbstractLogger}
import com.google.inject.{Inject, ImplementedBy, Singleton}
import play.api.Play.current

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

  lazy val logger = Logger("memcached.plugin")
  import java.io._

  class CustomSerializing extends SerializingTranscoder{

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
      val future = client.asyncGet(key, tc)
      try {
        val any = future.get(1, TimeUnit.SECONDS)
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
      } catch {
        case e: Throwable =>
          logger.error("An error has occured while getting the value from memcached" , e)
          future.cancel(false)
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


}
