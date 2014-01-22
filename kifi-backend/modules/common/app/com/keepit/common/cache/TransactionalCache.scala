package com.keepit.common.cache

import com.keepit.common.logging.{AccessLogTimer, AccessLog}
import com.keepit.serializer.{Serializer, BinaryFormat}
import play.api.libs.json._
import scala.concurrent.duration._

object TransactionalCache {
  implicit def getObjectCache[K <: Key[T], T](cache: TransactionalCache[K, T])(implicit txn: TransactionalCaching): ObjectCache[K, T] = cache(txn)
}

abstract class TransactionalCache[K <: Key[T], T](cache: ObjectCache[K, T], name: Option[String] = None) {
  val cacheName = name.getOrElse(this.getClass.getName)

  def apply(txn: TransactionalCaching): ObjectCache[K, T] = {
    if (txn.inCacheTransaction) {
      txn.getOrCreate(cacheName){ new TransactionLocalCache(cache) }
    } else {
      cache
    }
  }

  def direct: ObjectCache[K, T] = cache
}


abstract class JsonCacheImpl[K <: Key[T], T] private(cache: ObjectCache[K, T]) extends TransactionalCache(cache) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: Format[T]) =
      this(new FortyTwoCacheImpl(stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer(formatter)))
}

abstract class BinaryCacheImpl[K <: Key[T], T] private(cache: ObjectCache[K, T]) extends TransactionalCache(cache) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: BinaryFormat[T]) =
      this(new FortyTwoCacheImpl[K, T](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer(formatter)))
}

abstract class PrimitiveCacheImpl[K <: Key[P], P <: AnyVal] private(cache: ObjectCache[K, P]) extends TransactionalCache(cache) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*) =
      this(new FortyTwoCacheImpl[K, P](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer[P]))
}

abstract class StringCacheImpl[K <: Key[String]] private(cache: ObjectCache[K, String]) extends TransactionalCache(cache) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*) =
      this(new FortyTwoCacheImpl[K, String](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(Serializer.string))
}

