package com.keepit.social

import scala.concurrent.duration.Duration

import org.joda.time.DateTime

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics}
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.{State, ExternalId, Id}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class CommentWithBasicUser(
  id: ExternalId[Comment],
  createdAt: DateTime,
  text: String,
  user: BasicUser,
  permissions: State[CommentPermission],
  recipients: Seq[BasicUser]
)

object CommentWithBasicUser {
  implicit val format = (
    (__ \ 'id ).format(ExternalId.format[Comment]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'text).format[String] and
    (__ \ 'user).format[BasicUser] and
    (__ \ 'permissions).format(State.format[CommentPermission]) and
    (__ \ 'recipients).format[Seq[BasicUser]]
  )(CommentWithBasicUser.apply, unlift(CommentWithBasicUser.unapply))
}

case class CommentWithBasicUserKey(commentId: Id[Comment]) extends Key[CommentWithBasicUser] {
  val namespace = "comment_with_basic_user_by_comment_id"
  override val version = 4
  def toKey(): String = commentId.id.toString
}

class CommentWithBasicUserCache(
  stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[CommentWithBasicUserKey, CommentWithBasicUser](
  stats, accessLog,
  innermostPluginSettings, innerToOuterPluginSettings:_*)
