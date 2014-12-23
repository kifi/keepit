package com.keepit.common.cache

import com.keepit.common.logging.{ AccessLogTimer, AccessLog }
import com.keepit.serializer.{ NoCopyLocalSerializer, LocalSerializer, Serializer, BinaryFormat }
import play.api.libs.json._
import scala.concurrent.duration._

object TransactionalCache {
  implicit def getObjectCache[K <: Key[T], T](cache: TransactionalCache[K, T])(implicit txn: TransactionalCaching): ObjectCache[K, T] = cache(txn)
}

abstract class TransactionalCache[K <: Key[T], T](cache: ObjectCache[K, T], serializer: Serializer[T], localSerializerOpt: Option[LocalSerializer[T]] = None, name: Option[String] = None) {
  val cacheName = name.getOrElse(this.getClass.getName)

  def apply(txn: TransactionalCaching): ObjectCache[K, T] = {
    if (txn.inCacheTransaction) {
      txn.getOrCreate(cacheName) { new TransactionLocalCache(cache, serializer, localSerializerOpt) }
    } else {
      if (txn.isReadOnly) {
        new ReadOnlyCacheWrapper[K, T](cache, logging = true)
      } else {
        cache
      }
    }
  }

  def direct: ObjectCache[K, T] = cache
}

abstract class JsonCacheImpl[K <: Key[T], T] private (cache: ObjectCache[K, T], serializer: Serializer[T]) extends TransactionalCache(cache, serializer) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: Format[T]) =
    this(new FortyTwoCacheImpl(stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Serializer(formatter)), Serializer(formatter))
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration, Duration)*)(implicit formatter: Format[T]) =
    this(new FortyTwoCacheImpl(stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Serializer(formatter)), Serializer(formatter))
}

abstract class ImmutableJsonCacheImpl[K <: Key[T], T] private (cache: ObjectCache[K, T], serializer: Serializer[T]) extends TransactionalCache(cache, serializer, Some(NoCopyLocalSerializer[T]())) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: Format[T]) =
    this(new FortyTwoCacheImpl(stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Serializer(formatter)), Serializer(formatter))
}

abstract class BinaryCacheImpl[K <: Key[T], T] private (cache: ObjectCache[K, T], serializer: Serializer[T]) extends TransactionalCache(cache, serializer) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)(implicit formatter: BinaryFormat[T]) =
    this(new FortyTwoCacheImpl[K, T](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Serializer(formatter)), Serializer(formatter))
}

abstract class PrimitiveCacheImpl[K <: Key[P], P <: AnyVal] private (cache: ObjectCache[K, P], serializer: Serializer[P]) extends TransactionalCache(cache, serializer) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*) =
    this(new FortyTwoCacheImpl[K, P](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Serializer[P]), Serializer[P])

  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration, Duration)*) =
    this(new FortyTwoCacheImpl[K, P](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Serializer[P]), Serializer[P])
}

abstract class StringCacheImpl[K <: Key[String]] private (cache: ObjectCache[K, String], serializer: Serializer[String]) extends TransactionalCache(cache, serializer) {
  def this(
    stats: CacheStatistics, accessLog: AccessLog,
    innermostPluginSettings: (FortyTwoCachePlugin, Duration),
    innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*) =
    this(new FortyTwoCacheImpl[K, String](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Serializer.string), Serializer.string)
}

