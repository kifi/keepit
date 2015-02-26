package com.keepit.model

import scala.concurrent.duration.Duration

import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.Id
import com.keepit.serializer.ArrayBinaryFormat

case class UserConnectionIdKey(userId: Id[User]) extends Key[Array[Long]] {
  override val version = 4
  val namespace = "user_connections"
  def toKey(): String = userId.id.toString
}

class UserConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[UserConnectionIdKey, Array[Long]](stats, accessLog, inner, outer: _*)(ArrayBinaryFormat.longArrayFormat)

case class UserConnectionCountKey(userId: Id[User]) extends Key[Int] {
  val namespace = "user_connection_count"
  def toKey(): String = userId.id.toString
}

class UserConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[UserConnectionCountKey, Int](stats, accessLog, inner, outer: _*)

case class UserConnectionRelationshipKey(viewerId: Id[User], ownerId: Id[User]) extends Key[Seq[Id[User]]] {
  val namespace = "user_con_rel"
  def toKey(): String = ownerId.id.toString + ":" + viewerId.id.toString
}

class UserConnectionRelationshipCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserConnectionRelationshipKey, Seq[Id[User]]](stats, accessLog, inner, outer: _*)
