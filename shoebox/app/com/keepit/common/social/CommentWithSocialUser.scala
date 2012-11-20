package com.keepit.common.social

import com.keepit.model.Comment
import com.keepit.model.User
import java.sql.Connection

case class CommentWithSocialUser(user: UserWithSocial, comment: Comment)

object CommentWithSocialUser {
  def apply(comment: Comment)(implicit conn: Connection): CommentWithSocialUser = {
    CommentWithSocialUser(UserWithSocial.toUserWithSocial(User.get(comment.userId)), comment)
  }
}
