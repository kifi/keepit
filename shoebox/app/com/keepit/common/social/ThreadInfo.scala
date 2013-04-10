package com.keepit.common.social

import org.joda.time.DateTime
import java.sql.Connection
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.google.inject.Inject

case class ThreadInfo(externalId: ExternalId[Comment], recipients: Seq[BasicUser], digest: String, lastAuthor: ExternalId[User], messageCount: Long, hasAttachments: Boolean, createdAt: DateTime, lastCommentedAt: DateTime)

class ThreadInfoRepo @Inject() (
    commentRecipientRepo: CommentRecipientRepo,
    commentRepo: CommentRepo,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo) extends Logging {
  // TODO: Major optimizations needed!
  def load(comment: Comment, sessionUserOpt: Option[Id[User]] = None)(implicit session: RSession): ThreadInfo = {
    val children = commentRepo.getChildren(comment.id.get).reverse
    val childrenUsers = children map (c => c.userId)
    val allRecipients = commentRecipientRepo.getByComment(comment.id.get) map (cu => cu.userId.get)

    // We want to list recent commenters first, and then general recipients
    val recipients = filteredRecipients(comment.userId :: (childrenUsers ++ allRecipients).toList, sessionUserOpt)

    log.info("all recepient [%s], children [%s], originator [%s], filtered [%s], sessionUser [%s]".format(
        allRecipients.mkString, childrenUsers.mkString, comment.userId, recipients.mkString, sessionUserOpt.getOrElse("NA")))

    val lastComment = children.headOption.getOrElse(comment)
    ThreadInfo(
      externalId = comment.externalId,
      recipients = recipients,
      digest = lastComment.text, // todo: make smarter
      lastAuthor = userRepo.get(lastComment.userId).externalId,
      messageCount = children.size + 1,
      hasAttachments = false, // todo fix
      createdAt = comment.createdAt,
      lastCommentedAt = lastComment.createdAt
    )
  }

  private def filteredRecipients(userIds: Seq[Id[User]], sessionUser: Option[Id[User]])(implicit session: RSession): Seq[BasicUser] = {
    userIds.filterNot(recepientUserId => sessionUser map (_ == recepientUserId) getOrElse(false)).distinct map (u => basicUserRepo.load(u))
  }
}
