package com.keepit.model

import scala.concurrent.duration.Duration

import com.keepit.common.cache.{PrimitiveCacheImpl, BinaryCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics}
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.Id
import com.keepit.serializer.ArrayBinaryFormat

case class UserConnectionIdKey(userId: Id[User]) extends Key[Array[Long]] {
  override val version = 4
  val namespace = "user_connections"
  def toKey(): String = userId.id.toString
}

class UserConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends BinaryCacheImpl[UserConnectionIdKey, Array[Long]](stats, accessLog, inner, outer:_*)(ArrayBinaryFormat.longArrayFormat)

case class UserConnectionCountKey(userId: Id[User]) extends Key[Int] {
  val namespace = "user_connection_count"
  def toKey(): String = userId.id.toString
}

class UserConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends PrimitiveCacheImpl[UserConnectionCountKey, Int](stats, accessLog, inner, outer:_*)
