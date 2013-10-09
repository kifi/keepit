package com.keepit.model

import scala.concurrent.duration.Duration

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics}
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.Id
import com.keepit.serializer.TraversableFormat

case class SearchFriendsKey(userId: Id[User]) extends Key[Set[Id[User]]] {
  val namespace = "search_friends"
  def toKey(): String = userId.id.toString
}

class SearchFriendsCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SearchFriendsKey, Set[Id[User]]](
    stats, accessLog, inner, outer:_*)(TraversableFormat.set(Id.format[User]))
