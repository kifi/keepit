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
trait ShoeboxCache extends Plugin {
  def get(key: String): Option[Any]
  def getOrElse[A](key: String, expiration: Int)(orElse: => A)(implicit m: ClassManifest[A]): A // as per the Play! 2.x api, this side effects and also sets the key
  def getAs[T](key: String)(implicit m: ClassManifest[T]): Option[T] // @deprecated in Scala 2.10, todo(Andrew): Upgrade to Class Tag
  def remove(key: String): Unit
  def set(key: String, value: Any, expiration: Int = 0): Unit
  def getOrElse[A](expiration: Int)(key: Any*)(orElse: => A)(implicit m: ClassManifest[A]): A =
    getOrElse[A](key.map(_.toString.replaceAll("\u2028","")).mkString("\u2028"), expiration)(orElse)
}

class MemcachedCache extends ShoeboxCache {
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

class InMemoryCache extends ShoeboxCache {
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

package sandbox {
  import com.keepit.model.Comment

  trait Key[T] {
    val namespace: String
    def toKey(): String
    override def toString: String = namespace + ":" + toKey()
  }

  trait ObjectCache[K <: Key[T], T] {
    val ttl: Duration
  //    def serializer(obj: T): String
//    def deserializer(s: String): Option[T]

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

  trait AggressiveMemcached[K <: Key[T], T] {
    def get(key: K): Option[T] = throw new Exception
    def set(key: K, value: T): Unit = throw new Exception
  }

  case class CommentCount(count: Int)

  case class CommentCountKey(userId: String) extends Key[CommentCount] {
    val namespace = "comment_count_by_userid"
    def toKey(): String = userId + ""
  }

  class UserCommentCache(userId: String) extends ObjectCache[CommentCountKey, CommentCount] with AggressiveMemcached[CommentCountKey, CommentCount] {
    val ttl = 1 minute
  }

  package Transcoders {
    import net.spy.memcached.transcoders._
    import net.spy.memcached.CachedData
    import net.spy.memcached.compat.SpyObject

    class LongTranscoder extends SpyObject with net.spy.memcached.transcoders.Transcoder[Long] {
      private val jLongTranscoder = new LongTranscoder

      def asyncDecode(d: CachedData) = false
      def encode(i: Long): CachedData = jLongTranscoder.encode(i)
      def decode(d: CachedData): Long = jLongTranscoder.decode(d)
      def getMaxSize() = jLongTranscoder.getMaxSize
    }

    class ByteArrayTranscoder extends SpyObject with net.spy.memcached.transcoders.Transcoder[Array[Byte]] {
      private final val FLAGS = (8 << 8)

      def asyncDecode(d: CachedData) = false
      def encode(i: Array[Byte]): CachedData = new CachedData(FLAGS, i, getMaxSize())
      def decode(d: CachedData): Array[Byte] = {
        if (FLAGS == d.getFlags)
          d.getData()
        else {
          getLogger().error("Unexpected flags for long:  " + d.getFlags() + " wanted " + FLAGS)
          null
        }
      }
      def getMaxSize() = CachedData.MAX_SIZE
    }

  class StringTranscoder extends SpyObject with net.spy.memcached.transcoders.Transcoder[Array[Byte]] {
    private final val FLAGS = (8 << 8)
    private final val tu = new TranscoderUtils(true)

    def asyncDecode(d: CachedData) = false
    def encode(i: Array[Byte]): CachedData = new CachedData(FLAGS, i, getMaxSize())
    def decode(d: CachedData): Array[Byte] = {
      if (FLAGS == d.getFlags)
        d.getData()
      else {
        getLogger().error("Unexpected flags for long:  " + d.getFlags() + " wanted " + FLAGS)
        null
      }
    }
    def getMaxSize() = CachedData.MAX_SIZE
  }
  }


}
