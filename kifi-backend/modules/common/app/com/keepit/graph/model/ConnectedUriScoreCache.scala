package com.keepit.graph.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.User
import com.keepit.common.json.TraversableFormat

import scala.concurrent.duration.Duration

case class ConnectedUriScoreCacheKey(userId: Id[User], avoidFirstDegreeConnections: Boolean) extends Key[Seq[ConnectedUriScore]] {
  override val version = 1
  val namespace = "user_feed_score"
  def toKey(): String = s"userId=${userId.id.toString}#avoidFirstDegreeConnection=${avoidFirstDegreeConnections.toString}"
}

class ConnectedUriScoreCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ConnectedUriScoreCacheKey, Seq[ConnectedUriScore]](stats, accessLog, inner, outer: _*)(TraversableFormat.seq(ConnectedUriScore.format))

