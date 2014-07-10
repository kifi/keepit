package com.keepit.common.usersegment

import scala.concurrent.duration.Duration

import com.google.inject.Singleton
import com.keepit.common.cache._
import com.keepit.common.cache.Key
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.User

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class UserSegment(val value: Int)

object UserSegment {
  implicit def format = Json.format[UserSegment]
  def getDescription: PartialFunction[UserSegment, String] = {
    case UserSegment(0) => "many_friends_and_keeps"
    case UserSegment(1) => "few_friends_and_many_keeps"
    case UserSegment(2) => "many_friends_and_few_keeps"
    case UserSegment(3) => "few_friends_and_few_keeps"
    case u => s"unexpected_user_segment:${u.value}"
  }
}

object UserSegmentFactory {
  def apply(numKeeps: Int, numFriends: Int): UserSegment = {
    if (numKeeps > 50) {
      if (numFriends > 10) UserSegment(0) else UserSegment(1)
    } else {
      if (numFriends > 10) UserSegment(2) else UserSegment(3)
    }
  }
}

case class UserSegmentKey(userId: Id[User]) extends Key[UserSegment] {
  override val version = 2
  val namespace = "user_segment"
  def toKey(): String = userId.id.toString
}

class UserSegmentCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserSegmentKey, UserSegment](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
