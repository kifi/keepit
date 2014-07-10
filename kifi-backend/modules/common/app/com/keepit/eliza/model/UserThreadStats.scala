package com.keepit.eliza.model

import com.keepit.common.logging.AccessLog
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }

import scala.concurrent.duration._

import play.api.libs.json._

import play.api.libs.functional.syntax._
import com.keepit.common.db.Id
import com.keepit.model.User

case class UserThreadStats(all: Int, active: Int, started: Int)

object UserThreadStats {
  implicit def format = (
    (__ \ 'all).format[Int] and
    (__ \ 'active).format[Int] and
    (__ \ 'started).format[Int]
  )(UserThreadStats.apply, unlift(UserThreadStats.unapply))
}

case class UserThreadStatsForUserIdKey(userId: Id[User]) extends Key[UserThreadStats] {
  override val version = 0
  val namespace = "thread_stats_for_user"
  def toKey(): String = userId.id.toString
}

class UserThreadStatsForUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserThreadStatsForUserIdKey, UserThreadStats](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

