package com.keepit.common.social

import com.keepit.model.Comment
import com.keepit.model.User
import java.sql.Connection
import com.keepit.common.db.ExternalId
import com.keepit.model.CommentRecipient
import org.joda.time.DateTime
import com.keepit.common.db.Id

case class ThreadInfo(externalId: ExternalId[Comment], recipients: Seq[BasicUser], digest: String, lastAuthor: ExternalId[User], messageCount: Long, hasAttachments: Boolean, createdAt: DateTime, lastCommentedAt: DateTime)

object ThreadInfo {
  // TODO: Major optimizations needed!
  def apply(comment: Comment, userIdOpt: Option[Id[User]] = None)(implicit conn: Connection): ThreadInfo = {
    val children = Comment.getChildren(comment.id.get).reverse
    val childrenUsers = children map (c => c.userId)
    val allRecipients = CommentRecipient.getByComment(comment.id.get) map (cu => cu.userId.get)

    // We want to list recent commenters first, and then general recipients
    val sortedRecipients = (childrenUsers ++ allRecipients).filterNot(r => userIdOpt match {
      case Some(u) => r.id == u.id
      case None => false
    }).distinct map (u => BasicUser(User.get(u)))

    val lastComment = children.headOption.getOrElse(comment)
    ThreadInfo(
      externalId = comment.externalId,
      recipients = sortedRecipients,
      digest = lastComment.text, // todo: make smarter
      lastAuthor = User.get(lastComment.userId).externalId,
      messageCount = children.size + 1,
      hasAttachments = false, // todo fix
      createdAt = comment.createdAt,
      lastCommentedAt = lastComment.createdAt
    )
  }
}
