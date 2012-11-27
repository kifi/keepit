package com.keepit.common.social

import com.keepit.model.Comment
import com.keepit.model.User
import java.sql.Connection
import com.keepit.common.db.ExternalId
import com.keepit.model.CommentRecipient
import org.joda.time.DateTime

case class MessageDigest(externalId: ExternalId[Comment], recipients: Seq[BasicUser], digest: String, messageCount: Long, hasAttachments: Boolean, createdAt: DateTime, lastCommentedAt: DateTime)

object MessageDigest {
  // TODO: Major optimizations needed!
  def apply(comment: Comment)(implicit conn: Connection): MessageDigest = {
    val children = Comment.getChildren(comment.id.get).reverse
    val childrenUsers = children map (c => c.userId)
    val allRecipients = CommentRecipient.getByComment(comment.id.get) map (cu => cu.userId.get)

    // We want to list recent commenters first, and then general recipients
    val sortedRecipients = (childrenUsers ++ allRecipients).distinct map (u => BasicUser(User.get(u)))

    val lastComment = children.head
    MessageDigest(
      externalId = comment.externalId,
      recipients = sortedRecipients,
      digest = lastComment.text, // todo: make smarter
      messageCount = children.size + 1,
      hasAttachments = false, // todo fix
      createdAt = comment.createdAt,
      lastCommentedAt = lastComment.createdAt
    )
  }
}