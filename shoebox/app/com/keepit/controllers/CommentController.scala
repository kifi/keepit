package com.keepit.controllers

import java.sql.Connection
import scala.Option.option2Iterable
import scala.math.BigDecimal.long2bigDecimal
import com.keepit.common.async.dispatch
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.{CX, ExternalId, Id, State}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ElectronicMail, EmailAddresses, PostOffice}
import com.keepit.common.social.CommentWithSocialUser
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.common.social.UserWithSocial
import com.keepit.inject.inject
import com.keepit.model.{Bookmark, Comment, CommentRecipient, EmailAddress, NormalizedURI, SocialConnection, SocialUserInfo, User}
import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.serializer.UserWithSocialSerializer.userWithSocialSerializer
import com.keepit.serializer.CommentWithSocialUserSerializer._
import play.api.Play.current
import play.api.http.ContentTypes
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial
import securesocial.core.java.SecureSocial.SecuredAction

object CommentController extends FortyTwoController {

  def createComment(url: String,
                    text: String,
                    permission: String,
                    recipients: String = "",
                    parent: String) = AuthenticatedJsonAction { request =>
    val comment = CX.withConnection { implicit conn =>
      val userId = User.getOpt(request.userId).getOrElse(throw new Exception("Invalid userid"))
      val uri = NormalizedURI.getByNormalizedUrl(url) match {
        case Some(nuri) => nuri
        case None => NormalizedURI(title = "title", url = url).save
      }
      val parentIdOpt = parent match {
        case "" => None
        case p => Comment.getOpt(ExternalId[Comment](p)) match {
          case Some(p) => p.id
          case None => throw new Exception("Invalid parent provided!")
        }
      }

      permission.toLowerCase match {
        case "private" =>
          Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.PRIVATE, parent = parentIdOpt).save
        case "message" =>
          val newComment = Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.MESSAGE, parent = parentIdOpt).save
          createRecipients(newComment.id.get, recipients, parentIdOpt)
          newComment
        case "public" | "" =>
          Comment(normalizedURI = uri.id.get, userId = userId.id.get, text = text, permissions = Comment.Permissions.PUBLIC, parent = parentIdOpt).save
      }
    }

    notifyAnyRecipientsAsync(comment)

    Ok(JsObject(Seq("commentId" -> JsString(comment.externalId.id))))
  }

  def getComments(url: String) = AuthenticatedJsonAction { request =>
    val comments = CX.withConnection { implicit conn =>
      val user = User.get(request.userId)
      NormalizedURI.getByNormalizedUrl(url) match {
        case Some(normalizedURI) =>
          List(Comment.Permissions.PUBLIC -> publicComments(normalizedURI).map(CommentWithSocialUser(_)))
        case None =>
          List[(State[Comment.Permission],Seq[CommentWithSocialUser])]()
      }
    }
    Ok(commentWithSocialUserSerializer.writes(comments)).as(ContentTypes.JSON)
  }

  def getMessages(url: String) = AuthenticatedJsonAction { request =>
    val comments = CX.withConnection { implicit conn =>
      val user = User.get(request.userId)
      NormalizedURI.getByNormalizedUrl(url) match {
        case Some(normalizedURI) =>
          List(Comment.Permissions.MESSAGE -> messageComments(user.id.get, normalizedURI).map(CommentWithSocialUser(_)))
        case None =>
          List[(State[Comment.Permission],Seq[CommentWithSocialUser])]()
      }
    }
    Ok(commentWithSocialUserSerializer.writes(comments)).as(ContentTypes.JSON)
  }

  // TODO: getNotes()

  def getReplies(commentId: ExternalId[Comment]) = AuthenticatedJsonAction { request =>
    val replies = CX.withConnection { implicit conn =>
      val comment = Comment.get(commentId)
      val user = User.get(request.userId)
      if(hasPermission(user.id.get, comment.id.get))
        Comment.getChildren(comment.id.get) map { child => CommentWithSocialUser(child) }
      else
        Seq[CommentWithSocialUser]()
    }
    Ok(commentWithSocialUserSerializer.writes(replies)).as(ContentTypes.JSON)
  }

  def hasPermission(userId: Id[User], commentId: Id[Comment])(implicit conn: Connection): Boolean = {
    // TODO: write this
    true
  }


  // Given a list of comma separated external user ids, side effects and creates all the necessary recipients
  // For comments with a parent comment, adds recipients to parent comment instead.
  def createRecipients(commentId: Id[Comment], recipients: String, parentIdOpt: Option[Id[Comment]])(implicit conn: Connection) = {
    recipients.split(",").map(_.trim()) map { recipientId =>
      // Split incoming list of externalIds
      try {
        User.getOpt(ExternalId[User](recipientId)) match {
          case Some(recipientUser) =>
            log.info("Adding recipient %s to new comment %s".format(recipientUser.id.get, commentId))
            // When comment is a reply (has a parent), add recipient to parent if does not exist. Else, add to comment.
            parentIdOpt match {
              case Some(parentId) =>
                Some(CommentRecipient(commentId = parentId, userId = recipientUser.id).save)
              case None =>
                Some(CommentRecipient(commentId = commentId, userId = recipientUser.id).save)
            }
          case None =>
            // TODO: Add social User and email recipients as well
            log.info("Ignoring recipient %s for comment %s. User does not exist.".format(recipientId, commentId))
            None
        }
      }
      catch {
        case _ => None // It throws an exception if it fails ExternalId[User]. Just return None.
      }
    } flatten
  }

  private def allComments(userId: Id[User], normalizedURI: NormalizedURI)(implicit conn: Connection): List[(State[Comment.Permission],Seq[Comment])] =
    (Comment.Permissions.PUBLIC -> publicComments(normalizedURI)) ::
    (Comment.Permissions.MESSAGE -> messageComments(userId, normalizedURI)) ::
    (Comment.Permissions.PRIVATE -> privateComments(userId, normalizedURI)) :: Nil

  private def publicComments(normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    Comment.getPublicByNormalizedUri(normalizedURI.id.get)

  private def privateComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    Comment.getPrivateByNormalizedUri(normalizedURI.id.get, userId)

  private def messageComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    Comment.getMessagesByNormalizedUri(normalizedURI.id.get, userId)

  private def notifyAnyRecipientsAsync(comment: Comment) = dispatch({
    comment.permissions match {
      case Comment.Permissions.PUBLIC if comment.parent.isDefined =>
        CX.withConnection { implicit c =>
          val sender = User.get(comment.userId)
          val parent = Comment.get(comment.parent.get)
          val comments = parent :: Comment.getChildren(comment.parent.get).toList
          val uri = NormalizedURI.get(comment.normalizedURI)
          for (userId <- comments.map(_.userId).toSet - comment.userId) {
            val recipient = User.get(userId)
            val addrs = EmailAddress.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              inject[PostOffice].sendMail(ElectronicMail(
                  from = EmailAddresses.SUPPORT, to = addr, subject = "[new reply] " + uri.title,
                  htmlBody = views.html.email.newCommentReply(sender, recipient, uri, parent, comment).body))
            }
          }
        }
      case Comment.Permissions.MESSAGE =>
        CX.withConnection { implicit c =>
          val sender = User.get(comment.userId)
          val uri = NormalizedURI.get(comment.normalizedURI)
          val subjectPrefix = if (comment.parent.isDefined) "[new reply] " else "[new message] "
          val recipients = Comment.getRecipients(comment.parent.getOrElse(comment.id.get))
          for (userId <- recipients.map(_.userId.get).toSet - comment.userId) {
            val recipient = User.get(userId)
            val addrs = EmailAddress.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              inject[PostOffice].sendMail(ElectronicMail(
                  from = EmailAddresses.SUPPORT, to = addr, subject = subjectPrefix + uri.title,
                  htmlBody = views.html.email.newMessage(sender, recipient, uri, comment).body))
            }
          }
        }
      case _ =>

    }
  }, {e => log.error("Could not persist emails for comment %s".format(comment.id.get), e)})

}
