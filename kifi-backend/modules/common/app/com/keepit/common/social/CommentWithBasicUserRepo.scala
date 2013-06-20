package com.keepit.common.social

import com.keepit.model.{CommentPermissions, CommentRecipientRepo, Comment}
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession

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
