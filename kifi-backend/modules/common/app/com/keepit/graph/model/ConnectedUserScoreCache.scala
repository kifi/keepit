package com.keepit.graph.model

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.User
import com.keepit.common.json.TraversableFormat

import scala.concurrent.duration.Duration

case class ConnectedUserScoreCacheKey(userId: Id[User], avoidFirstDegreeConnections: Boolean) extends Key[Seq[ConnectedUserScore]] {
  override val version = 1
  val namespace = "user_connection_score"
  def toKey(): String = s"userId=${userId.id.toString}#avoidFirstDegreeConnection=${avoidFirstDegreeConnections.toString}"
}

class ConnectedUserScoreCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration, Duration), outer: (FortyTwoCachePlugin, Duration, Duration)*)
  extends JsonCacheImpl[ConnectedUserScoreCacheKey, Seq[ConnectedUserScore]](stats, accessLog, inner, outer: _*)(TraversableFormat.seq(ConnectedUserScore.format))

