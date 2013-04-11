package com.keepit.controllers.core

import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.google.inject.Inject
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.social.{CommentWithBasicUser, CommentWithBasicUserRepo}
import com.keepit.common.logging._
import com.keepit.common.social.{ThreadInfo, ThreadInfoRepo}

class PaneDetails @Inject() (
  db: Database,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  normalizedURIRepo: NormalizedURIRepo,
  commentReadRepo: CommentReadRepo,
  commentWithBasicUserRepo: CommentWithBasicUserRepo,
  threadInfoRepo: ThreadInfoRepo)
    extends Logging {

  /** Returns a page's comments and marks them read for the viewer. */
  def getComments(viewerUserId: Id[User], url: String): Seq[CommentWithBasicUser] = {
    val (comments, commentRead) = db.readOnly { implicit session =>
      val normUriOpt = normalizedURIRepo.getByNormalizedUrl(url)

      val comments = normUriOpt map { normUri =>
        getComments(normUri.id.get)
      } getOrElse Nil

      val lastCommentId = comments match {
        case Nil => None
        case commentList => Some(commentList.map(_.comment.id.get).maxBy(_.id))
      }

      val commentRead = normUriOpt.flatMap { normUri =>
        lastCommentId.map { lastCom =>
          commentReadRepo.getByUserAndUri(viewerUserId, normUri.id.get) match {
            case Some(commentRead) => // existing CommentRead entry for this user/url
              if (commentRead.lastReadId.id < lastCom.id) Some(commentRead.withLastReadId(lastCom))
              else None
            case None =>
              Some(CommentRead(userId = viewerUserId, uriId = normUri.id.get, lastReadId = lastCom))
          }
        }
      }
      (comments, commentRead.flatten)
    }

    commentRead foreach { cr =>
      db.readWrite { implicit session =>
        commentReadRepo.save(cr)
      }
    }

    comments
  }

  /** Returns a page's comments. */
  def getComments(nUriId: Id[NormalizedURI])(implicit session: RSession): Seq[CommentWithBasicUser] =
    commentRepo.getPublic(nUriId).map(commentWithBasicUserRepo.load)

  /** Returns a user's message threadlist for a specific page. Result does not include all full message bodies. */
  def getMessageThreadList(userId: Id[User], url: String): Seq[ThreadInfo] = {
    db.readOnly { implicit s =>
      normalizedURIRepo.getByNormalizedUrl(url) map { uri =>
        getMessageThreadList(userId, uri.id.get)
      } getOrElse Nil
    }
  }

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

  /** Returns the messages in a specific thread and marks them read for the viewer. */
  def getMessageThread(viewerUserId: Id[User], messageId: ExternalId[Comment]): Seq[CommentWithBasicUser] = {
    val (messages, commentReadToSave) = db.readOnly{ implicit session =>
      val message = commentRepo.get(messageId)
      val parent = message.parent.map(commentRepo.get).getOrElse(message)

      val messages: Seq[CommentWithBasicUser] = parent +: commentRepo.getChildren(parent.id.get) map { msg =>
        commentWithBasicUserRepo.load(msg)
      }

      // mark latest message as read for viewer

      val lastMessageId = messages.map(_.comment.id.get).maxBy(_.id)

      val commentReadToSave = commentReadRepo.getByUserAndParent(viewerUserId, parent.id.get) match {
        case Some(cr) if cr.lastReadId == lastMessageId =>
          None
        case Some(cr) =>
          Some(cr.withLastReadId(lastMessageId))
        case None =>
          Some(CommentRead(userId = viewerUserId, uriId = parent.uriId, parentId = parent.id, lastReadId = lastMessageId))
      }
      (messages, commentReadToSave)
    }

    commentReadToSave.map { cr =>
      db.readWrite{ implicit session =>
        commentReadRepo.save(cr)
      }
    }

    messages
  }

}
