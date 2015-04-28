package com.keepit.model

import scala.concurrent.duration.Duration

import com.keepit.common.cache.{ BinaryCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.Id
import com.keepit.serializer.ArrayBinaryFormat

case class SearchFriendsKey(userId: Id[User]) extends Key[Set[Id[User]]] {
  val namespace = "search_friends"
  override val version: Int = 3
  def toKey(): String = userId.id.toString
}

class SearchFriendsCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[SearchFriendsKey, Set[Id[User]]](stats, accessLog, inner, outer: _*)(ArrayBinaryFormat.longArrayFormat.map(_.map(_.id).toArray, _.map(Id[User](_)).toSet))
