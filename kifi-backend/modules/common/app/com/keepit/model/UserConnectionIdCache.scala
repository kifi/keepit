package com.keepit.model

import scala.concurrent.duration.Duration

import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.Id
import com.keepit.serializer.ArrayBinaryFormat

case class UserConnectionIdKey(userId: Id[User]) extends Key[Array[Long]] {
  override val version = 5
  val namespace = "user_connections"
  def toKey(): String = userId.id.toString
}

class UserConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[UserConnectionIdKey, Array[Long]](stats, accessLog, inner, outer: _*)(ArrayBinaryFormat.longArrayFormat)

case class UserConnectionCountKey(userId: Id[User]) extends Key[Int] {
  override val version = 2
  val namespace = "user_connection_count"
  def toKey(): String = userId.id.toString
}

class UserConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[UserConnectionCountKey, Int](stats, accessLog, inner, outer: _*)

case class UserMutualConnectionCountKey(user1: Id[User], user2: Id[User]) extends Key[Int] {
  override val version = 3
  val namespace = "user_mutual_conn_count"
  def toKey(): String = {
    val id1 = user1.id
    val id2 = user2.id
    (id1.min(id2)).toString + ":" + (id1.max(id2)).toString
  }
}

class UserMutualConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[UserMutualConnectionCountKey, Int](stats, accessLog, inner, outer: _*)
