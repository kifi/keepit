package com.keepit.common.social

import play.api.Play.current
import java.sql.Connection
import com.keepit.inject.inject
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model.CommentRecipient
import com.google.inject.Inject

case class CommentWithBasicUser(user: BasicUser, comment: Comment, replyCount: Long, recipients: Seq[BasicUser])

class CommentWithBasicUserRepo @Inject() (basicUserRepo: BasicUserRepo, commentRecipientRepo: CommentRecipientRepo, commentRepo: CommentRepo) {
  def load(comment: Comment)(implicit session: RSession): CommentWithBasicUser = {
    CommentWithBasicUser(
      basicUserRepo.load(comment.userId),
      comment,
      commentRepo.getChildCount(comment.id.get),
      if(comment.permissions != CommentPermissions.MESSAGE) {
        Nil
      } else {
        commentRecipientRepo.getByComment(comment.id.get) map { cr =>
          basicUserRepo.load(cr.userId.get)
        }
      }
    )
  }
}