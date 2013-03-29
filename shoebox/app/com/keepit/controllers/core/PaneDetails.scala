package com.keepit.controllers.core

import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.google.inject.Inject
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.social.{CommentWithSocialUser, CommentWithSocialUserRepo}
import com.keepit.common.logging._
import com.keepit.common.social.{ThreadInfo, ThreadInfoRepo}

class PaneDetails @Inject() (
  db: Database,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  normalizedURIRepo: NormalizedURIRepo,
  commentReadRepo: CommentReadRepo,
  commentWithSocialUserRepo: CommentWithSocialUserRepo,
  threadInfoRepo: ThreadInfoRepo
) extends Logging {

  def getComments(userId: Id[User], url: String): Seq[CommentWithSocialUser] = {
    val (comments, commentRead) = db.readOnly { implicit session =>
      val normUriOpt = normalizedURIRepo.getByNormalizedUrl(url)

      val comments = normUriOpt map { normalizedURI =>
          publicComments(normalizedURI).map(commentWithSocialUserRepo.load(_))
        } getOrElse Nil

      val lastCommentId = comments match {
        case Nil => None
        case commentList => Some(commentList.map(_.comment.id.get).maxBy(_.id))
      }

      val commentRead = normUriOpt.flatMap { normUri =>
        lastCommentId.map { lastCom =>
          commentReadRepo.getByUserAndUri(userId, normUri.id.get) match {
            case Some(commentRead) => // existing CommentRead entry for this user/url
              if (commentRead.lastReadId.id < lastCom.id) Some(commentRead.withLastReadId(lastCom))
              else None
            case None =>
              Some(CommentRead(userId = userId, uriId = normUri.id.get, lastReadId = lastCom))
          }
        }
      }
      (comments, commentRead.flatten)
    }

    commentRead.map { cr =>
      db.readWrite { implicit session =>
        commentReadRepo.save(cr)
      }
    }

    comments
  }

  def getMessageThreadList(userId: Id[User], url: String): Seq[ThreadInfo] = {
    db.readOnly { implicit s =>
      normalizedURIRepo.getByNormalizedUrl(url) map { normalizedURI =>
          messageComments(userId, normalizedURI).map(threadInfoRepo.load(_, Some(userId))).reverse
        } getOrElse Nil
    }
  }

  def getMessageThread(userId: Id[User], commentId: ExternalId[Comment]): Seq[CommentWithSocialUser] = {
    val (messages, commentReadToSave) = db.readOnly{ implicit session =>
      val comment = commentRepo.get(commentId)
      val parent = comment.parent.map(commentRepo.get).getOrElse(comment)

      val messages: Seq[CommentWithSocialUser] = parent +: commentRepo.getChildren(parent.id.get) map { msg =>
        commentWithSocialUserRepo.load(msg)
      }

      // mark latest message as read for viewer

      val lastMessageId = messages.map(_.comment.id.get).maxBy(_.id)

      val commentReadToSave = commentReadRepo.getByUserAndParent(userId, parent.id.get) match {
        case Some(cr) if cr.lastReadId == lastMessageId =>
          None
        case Some(cr) =>
          Some(cr.withLastReadId(lastMessageId))
        case None =>
          Some(CommentRead(userId = userId, uriId = parent.uriId, parentId = parent.id, lastReadId = lastMessageId))
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

  private def publicComments(normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit session: RSession) =
    commentRepo.getPublic(normalizedURI.id.get)

  private def messageComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit session: RSession) =
    commentRepo.getMessages(normalizedURI.id.get, userId)

}