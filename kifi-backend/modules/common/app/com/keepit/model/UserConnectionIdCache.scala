package com.keepit.model

import scala.concurrent.duration.Duration

import com.keepit.common.cache.{PrimitiveCacheImpl, JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.db.Id
import com.keepit.serializer.TraversableFormat

case class UserConnectionIdKey(userId: Id[User]) extends Key[Set[Id[User]]] {
  override val version = 2
  val namespace = "user_connections"
  def toKey(): String = userId.id.toString
}

class UserConnectionIdCache(inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[UserConnectionIdKey, Set[Id[User]]](inner, outer:_*)(TraversableFormat.set(Id.format[User]))

case class UserConnectionCountKey(userId: Id[User]) extends Key[Int] {
  val namespace = "user_connection_count"
  def toKey(): String = userId.id.toString
}

class UserConnectionCountCache(inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends PrimitiveCacheImpl[UserConnectionCountKey, Int](inner, outer:_*)
