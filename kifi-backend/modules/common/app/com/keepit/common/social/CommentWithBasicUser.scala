package com.keepit.common.social

import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.db.Id
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import scala.concurrent.duration.Duration

case class CommentWithBasicUser(user: BasicUser, comment: Comment, recipients: Seq[BasicUser])

object CommentWithBasicUser {
  implicit val commentWithBasicUserFormat = Json.format[CommentWithBasicUser]
}

case class CommentWithBasicUserKey(commentId: Id[Comment]) extends Key[CommentWithBasicUser] {
  val namespace = "comment_with_basic_user_by_comment_id"
  override val version = 2
  def toKey(): String = commentId.id.toString
}

class CommentWithBasicUserCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[CommentWithBasicUserKey, CommentWithBasicUser](innermostPluginSettings, innerToOuterPluginSettings:_*)
