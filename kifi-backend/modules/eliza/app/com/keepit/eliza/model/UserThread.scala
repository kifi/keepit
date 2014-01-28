package com.keepit.eliza.model

import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.common.db.{Model, Id}
import com.keepit.model.{User, NormalizedURI}

import play.api.libs.json._

import org.joda.time.DateTime


import scala.concurrent.duration._

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import play.api.libs.functional.syntax._
import scala.Some

case class Notification(thread: Id[MessageThread], message: Id[Message])

case class UserThreadActivity(id: Id[UserThread], threadId: Id[MessageThread], userId: Id[User], lastActive: Option[DateTime], started: Boolean, lastSeen: Option[DateTime])

case class UserThread(
    id: Option[Id[UserThread]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    user: Id[User],
    thread: Id[MessageThread],
    uriId: Option[Id[NormalizedURI]],
    lastSeen: Option[DateTime],
    unread: Boolean = false,
    muted: Boolean = false,
    lastMsgFromOther: Option[Id[Message]],
    lastNotification: JsValue,
    notificationUpdatedAt: DateTime = currentDateTime,
    notificationLastSeen: Option[DateTime] = None,
    notificationEmailed: Boolean = false,
    replyable: Boolean = true,
    lastActive: Option[DateTime] = None, //Contains the 'createdAt' timestamp of the last message this user sent on this thread
    started: Boolean = false //Whether or not this thread was started by this user
  )
  extends Model[UserThread] {

  def withId(id: Id[UserThread]): UserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt=updateTime)
}

case class UserThreadStats(all: Int, active: Int, started: Int)

object UserThreadStats {
  implicit def format = (
    (__ \ 'all).format[Int] and
    (__ \ 'active).format[Int] and
    (__ \ 'started).format[Int]
  )(UserThreadStats.apply, unlift(UserThreadStats.unapply))
}

case class UserThreadStatsForUserIdKey(userId:Id[User]) extends Key[UserThreadStats] {
  override val version = 0
  val namespace = "thread_stats_for_user"
  def toKey():String = userId.id.toString
}

class UserThreadStatsForThreadIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserThreadStatsForUserIdKey, UserThreadStats](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

