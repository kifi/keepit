package com.keepit.common.cache

import scala.concurrent.duration.Duration
import com.keepit.common.logging.AccessLog
import net.codingwell.scalaguice.InjectorExtensions._

import java.io.{ ByteArrayOutputStream, ObjectOutputStream, ObjectInputStream, ByteArrayInputStream }

import net.codingwell.scalaguice.InjectorExtensions.ScalaInjector

import com.keepit.FortyTwoGlobal
import com.keepit.common.logging.Logging
import com.keepit.serializer.BinaryFormat
import com.keepit.common.logging.{ AccessLogTimer, AccessLog }

import play.api.Application
import play.api.cache.{ CacheAPI, CachePlugin }
import play.api.Play._

class PlayCachePlugin(app: Application) extends CachePlugin {
  override lazy val enabled = !app.configuration.getString("playcache").filter(_ == "disabled").isDefined
  override def onStart() {}
  override def onStop() {}
  lazy val api = app.global.asInstanceOf[FortyTwoGlobal].injector.instance[PlayCacheApi]
}

class PlayCacheApi(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends BinaryCacheImpl[PlayCacheKey, Any](stats, accessLog, inner, outer: _*)(PlayBinaryFormat) with CacheAPI with Logging {

  import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

  def set(key: String, value: Any, expiration: Int): Unit = {
    log.info(s"Setting cache key: $key, value: $value")
    set(PlayCacheKey(key), value)
  }
  def get(key: String): Option[Any] = {
    val g = get(PlayCacheKey(key))
    log.info(s"Getting cache key: $key, value: $g")
    g
  }
  def remove(key: String): Unit = {
    log.info(s"Removing cache key: $key")
    remove(PlayCacheKey(key))
  }
}

case class PlayCacheKey(key: String) extends Key[Any] {
  val namespace = "play_cache"
  def toKey() = key
}

private object PlayBinaryFormat extends BinaryFormat[Any] {

  protected def reads(obj: Array[Byte], offset: Int, length: Int): Any = {
    new ObjectInputStream(new ByteArrayInputStream(obj, offset, length)).readObject()
  }
  protected def writes(prefix: Byte, value: Any): Array[Byte] = {
    val out = new ByteArrayOutputStream()

    out.write(prefix) // we have something

    new ObjectOutputStream(out).writeObject(value)
    out.toByteArray
  }
}
