package com.keepit.common.social

import play.api.Play.current
import java.sql.Connection
import com.keepit.inject.inject
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model.CommentRecipient

case class CommentWithSocialUser(user: UserWithSocial, comment: Comment, replyCount: Long, recipients: Seq[UserWithSocial])

class CommentWithSocialUserRepo {
  def load(comment: Comment)(implicit session: RSession): CommentWithSocialUser = {
    val userRepo = inject[UserRepo]
    val commentRepo = inject[CommentRepo]
    val commentRecipientRepo = inject[CommentRecipientRepo]
    CommentWithSocialUser(
      UserWithSocial.toUserWithSocial(userRepo.get(comment.userId)),
      comment,
      commentRepo.getChildCount(comment.id.get),
      if(comment.permissions != CommentPermissions.MESSAGE) {
        Nil
      } else {
        commentRecipientRepo.getByComment(comment.id.get) map { cr => 
          UserWithSocial.toUserWithSocial(userRepo.get(cr.userId.get)) 
        }
      }
    )
  }
}