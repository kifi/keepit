package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.model.{CommentPermissions, CommentRecipientRepo, Comment}
import com.keepit.social.{CommentWithBasicUserCache, CommentWithBasicUserKey, CommentWithBasicUser}

class CommentWithBasicUserRepo @Inject() (basicUserRepo: BasicUserRepo, commentRecipientRepo: CommentRecipientRepo, commentCache: CommentWithBasicUserCache) {
  def load(comment: Comment)(implicit session: RSession): CommentWithBasicUser = commentCache.getOrElse(CommentWithBasicUserKey(comment.id.get)) {
    val basicUser = basicUserRepo.load(comment.userId)
    val recipients =
      if (comment.permissions == CommentPermissions.MESSAGE)
        commentRecipientRepo.getByComment(comment.id.get) map { cr => basicUserRepo.load(cr.userId.get) }
      else Nil
    CommentWithBasicUser(
      id = comment.externalId,
      createdAt = comment.createdAt,
      text = comment.text,
      user = basicUser,
      permissions = comment.permissions,
      recipients = recipients
    )
  }
}
