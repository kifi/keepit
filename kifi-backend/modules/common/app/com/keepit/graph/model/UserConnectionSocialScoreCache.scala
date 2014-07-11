package com.keepit.graph.model

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.User
import com.keepit.serializer.TraversableFormat

import scala.concurrent.duration.Duration

case class UserConnectionSocialScoreCacheKey(userId: Id[User]) extends Key[Seq[UserConnectionSocialScore]] {
  override val version = 0
  val namespace = "user_connection_score"
  def toKey(): String = userId.id.toString
}

class UserConnectionSocialScoreCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserConnectionSocialScoreCacheKey, Seq[UserConnectionSocialScore]](stats, accessLog, inner, outer: _*)(TraversableFormat.seq(UserConnectionSocialScore.format))

