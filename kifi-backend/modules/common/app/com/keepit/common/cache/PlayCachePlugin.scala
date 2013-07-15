package com.keepit.common.cache

import scala.concurrent.duration.Duration

import java.io.{ByteArrayOutputStream, ObjectOutputStream, ObjectInputStream, ByteArrayInputStream}

import net.codingwell.scalaguice.InjectorExtensions.enrichInjector

import com.keepit.FortyTwoGlobal
import com.keepit.common.logging.Logging
import com.keepit.serializer.BinaryFormat

import play.api.Application
import play.api.cache.{CacheAPI, CachePlugin}

class PlayCachePlugin(app: Application) extends CachePlugin {
  override lazy val enabled = !app.configuration.getString("playcache").filter(_ == "disabled").isDefined
  override def onStart() {}
  override def onStop() {}
  lazy val api = app.global.asInstanceOf[FortyTwoGlobal].injector.instance[PlayCacheApi]
}

class PlayCacheApi(inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[PlayCacheKey, Any](inner, outer: _*)(PlayBinaryFormat) with CacheAPI with Logging {

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
  def reads(obj: Array[Byte]): Any = {
    new ObjectInputStream(new ByteArrayInputStream(obj)).readObject()
  }
  def writes(value: Any): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    new ObjectOutputStream(out).writeObject(value)
    out.toByteArray
  }
}
