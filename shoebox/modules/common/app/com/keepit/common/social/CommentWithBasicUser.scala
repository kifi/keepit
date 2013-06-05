package com.keepit.common.social

import play.api.Play.current
import java.sql.Connection
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model.CommentRecipient
import com.google.inject.Inject
import com.keepit.common.cache._
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class CommentWithBasicUser(user: BasicUser, comment: Comment, recipients: Seq[BasicUser])

object CommentWithBasicUser {
  implicit val commentWithBasicUserFormat = Json.format[CommentWithBasicUser]
}

case class CommentWithBasicUserKey(commentId: Id[Comment]) extends Key[CommentWithBasicUser] {
  val namespace = "comment_with_basic_user_by_comment_id"
  def toKey(): String = commentId.id.toString
}

class CommentWithBasicUserCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[CommentWithBasicUserKey, CommentWithBasicUser] {
  val ttl = 7 days
  def deserialize(obj: Any): CommentWithBasicUser = parseJson(obj)
  def serialize(commentWithBasicUser: CommentWithBasicUser) = Json.toJson(commentWithBasicUser)
}

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
