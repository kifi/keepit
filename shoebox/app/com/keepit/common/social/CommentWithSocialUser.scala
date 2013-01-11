package com.keepit.common.social

import com.keepit.model.Comment
import com.keepit.model.{User, UserCxRepo}
import java.sql.Connection
import com.keepit.common.db.ExternalId
import com.keepit.model.CommentRecipient

case class CommentWithSocialUser(user: UserWithSocial, comment: Comment, replyCount: Long, recipients: Seq[UserWithSocial])

object CommentWithSocialUser {
  // TODO: Major optimizations needed!
  def apply(comment: Comment)(implicit conn: Connection): CommentWithSocialUser = {
    CommentWithSocialUser(
      UserWithSocial.toUserWithSocial(UserCxRepo.get(comment.userId)),
      comment,
      Comment.getChildCount(comment.id.get),
      if(comment.permissions != Comment.Permissions.MESSAGE) {
        Nil
      } else {
        CommentRecipient.getByComment(comment.id.get) map { cr => UserWithSocial.toUserWithSocial(UserCxRepo.get(cr.userId.get)) }
      }
    )
  }
}
