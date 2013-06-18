package com.keepit.common.social

import com.keepit.common.db.Id
import com.keepit.model.{CommentPermissions, CommentRecipientRepo, Comment}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import scala.concurrent.duration.Duration
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession

case class CommentWithBasicUserKey(commentId: Id[Comment]) extends Key[CommentWithBasicUser] {
  val namespace = "comment_with_basic_user_by_comment_id"
  override val version = 2
  def toKey(): String = commentId.id.toString
}

class CommentWithBasicUserCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[CommentWithBasicUserKey, CommentWithBasicUser](innermostPluginSettings, innerToOuterPluginSettings:_*)

class CommentWithBasicUserRepo @Inject() (basicUserRepo: BasicUserRepo, commentRecipientRepo: CommentRecipientRepo, commentCache: CommentWithBasicUserCache) {
  def load(comment: Comment)(implicit session: RSession): CommentWithBasicUser = commentCache.getOrElse(CommentWithBasicUserKey(comment.id.get)) {
    CommentWithBasicUser(
      basicUserRepo.load(comment.userId),
      comment,
      if (comment.permissions != CommentPermissions.MESSAGE) {
        Nil
      } else {
        commentRecipientRepo.getByComment(comment.id.get) map { cr =>
          basicUserRepo.load(cr.userId.get)
        }
      }
    )
  }
}
