package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.logging._
import com.keepit.common.social.{CommentWithBasicUser, CommentWithBasicUserRepo}
import com.keepit.common.social.{ThreadInfo, ThreadInfoRepo}
import com.keepit.model._

class PaneDetails @Inject() (
  db: Database,
  commentRepo: CommentRepo,
  normalizedURIRepo: NormalizedURIRepo,
  commentWithBasicUserRepo: CommentWithBasicUserRepo,
  threadInfoRepo: ThreadInfoRepo)
    extends Logging {

  /** Returns a page's comments. */
  def getComments(nUriId: Id[NormalizedURI])(implicit session: RSession): Seq[CommentWithBasicUser] =
    commentRepo.getPublic(nUriId).map(commentWithBasicUserRepo.load)

  /** Returns a user's message threadlist for a specific page. Result does not include all full message bodies. */
  def getMessageThreadList(userId: Id[User], nUriId: Id[NormalizedURI])(implicit session: RSession): Seq[ThreadInfo] =
    commentRepo.getMessages(nUriId, userId).map(threadInfoRepo.load(_, Some(userId))).reverse

  /** Returns the messages in a specific thread. */
  def getMessageThread(messageId: ExternalId[Comment]): (NormalizedURI, Seq[CommentWithBasicUser]) = {
    db.readOnly { implicit session =>
      val message = commentRepo.get(messageId)
      val parent = message.parent.map(commentRepo.get).getOrElse(message)
      val messages = parent +: commentRepo.getChildren(parent.id.get) map commentWithBasicUserRepo.load
      (normalizedURIRepo.get(parent.uriId), messages)
    }
  }

}
