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
import com.keepit.common.social.UserWithSocial.toUserWithSocial
import com.keepit.inject.inject
import com.keepit.model.{Bookmark, Comment, CommentRecipient, EmailAddress, Follow, NormalizedURI, SocialConnection, SocialUserInfo, User}
import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.serializer.UserWithSocialSerializer.userWithSocialSerializer
import com.keepit.serializer.CommentWithSocialUserSerializer.commentWithSocialUserSerializer
import com.keepit.serializer.ThreadInfoSerializer.threadInfoSerializer
import play.api.Play.current
import play.api.http.ContentTypes
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial
import securesocial.core.java.SecureSocial.SecuredAction
import com.keepit.common.social.ThreadInfo

object CommentController extends FortyTwoController {

  def createComment(url: String,
                    text: String,
                    permission: String,
                    recipients: String = "",
                    parent: String) = AuthenticatedJsonAction { request =>
    val comment = CX.withConnection { implicit conn =>

      if(text.trim.isEmpty)
        throw new Exception("Emoty comments are not allowed")
        
      val userId = request.userId
      val uri = NormalizedURI.getByNormalizedUrl(url).getOrElse(NormalizedURI(url = url).save)
      val parentIdOpt = parent match {
        case "" => None
        case id => Comment.get(ExternalId[Comment](id)).id
      }

      permission.toLowerCase match {
        case "private" =>
          Comment(uriId = uri.id.get, userId = userId, text = text, permissions = Comment.Permissions.PRIVATE, parent = parentIdOpt).save
        case "message" =>
          val newComment = Comment(uriId = uri.id.get, userId = userId, text = text, permissions = Comment.Permissions.MESSAGE, parent = parentIdOpt).save
          createRecipients(newComment.id.get, recipients, parentIdOpt)
          newComment
        case "public" | "" =>
          Comment(uriId = uri.id.get, userId = userId, text = text, permissions = Comment.Permissions.PUBLIC, parent = parentIdOpt).save
        case _ =>
          throw new Exception("Invalid comment permission")
      }
    }

    notifyRecipientsAsync(comment)

    comment.permissions match {
      case Comment.Permissions.PUBLIC =>
        Ok(JsObject(Seq("commentId" -> JsString(comment.externalId.id))))
      case Comment.Permissions.MESSAGE =>
        val threadInfo = CX.withConnection{ implicit c => CommentWithSocialUser(comment) }
        Ok(JsObject(Seq("message" -> commentWithSocialUserSerializer.writes(threadInfo))))
      case _ =>
        Ok(JsObject(Seq("commentId" -> JsString(comment.externalId.id))))
    }
  }

  def getUpdates(url: String) = AuthenticatedJsonAction { request =>
    val (messageCount, privateCount, publicCount) = CX.withReadOnlyConnection { implicit conn =>
      val uriId = NormalizedURI.getByNormalizedUrl(url).get.id.get
      val userId = request.userId
      val messageCount = Comment.getMessagesWithChildrenCount(uriId, userId)
      val privateCount = Comment.getPrivateCount(uriId, userId)
      val publicCount = Comment.getPublicCount(uriId)
      (messageCount, privateCount, publicCount)
    }
    Ok(JsObject(List(
        "publicCount" -> JsNumber(publicCount),
        "privateCount" -> JsNumber(privateCount),
        "messageCount" -> JsNumber(messageCount),
        "countSum" -> JsNumber(publicCount + privateCount + messageCount)
    )))
  }

  def getComments(url: String) = AuthenticatedJsonAction { request =>
    val comments = CX.withConnection { implicit conn =>
      NormalizedURI.getByNormalizedUrl(url) map { normalizedURI =>
          publicComments(normalizedURI).map(CommentWithSocialUser(_))
        } getOrElse Nil
    }
    Ok(commentWithSocialUserSerializer.writes(Comment.Permissions.PUBLIC -> comments))
  }

  def getMessageThreadList(url: String) = AuthenticatedJsonAction { request =>
    val comments = CX.withConnection { implicit conn =>
      NormalizedURI.getByNormalizedUrl(url) map { normalizedURI =>
          messageComments(request.userId, normalizedURI).map(ThreadInfo(_, Some(request.userId))).reverse
        } getOrElse Nil
    }
    log.info("comments for url %s:\n%s".format(url, comments mkString "\n"))
    Ok(threadInfoSerializer.writes(Comment.Permissions.MESSAGE -> comments))
  }

  def getMessageThread(commentId: ExternalId[Comment]) = AuthenticatedJsonAction { request =>
    val replies = CX.withConnection { implicit conn =>
      val comment = Comment.get(commentId)
      if (true) // TODO: hasPermission(user.id.get, comment.id.get) ???????????????
        (Seq(comment) ++ Comment.getChildren(comment.id.get) map { child => CommentWithSocialUser(child) })
      else
          Nil
    }
    Ok(commentWithSocialUserSerializer.writes(Comment.Permissions.MESSAGE -> replies))
  }

  // TODO: delete once no beta users have old plugin supporting replies
  def getReplies(commentId: ExternalId[Comment]) = AuthenticatedJsonAction { request =>
    val replies = CX.withConnection { implicit conn =>
      val comment = Comment.get(commentId)
      val user = User.get(request.userId)
      if (true) // TODO: hasPermission(user.id.get, comment.id.get) ??????????????
        Comment.getChildren(comment.id.get) map { child => CommentWithSocialUser(child) }
      else
          Nil
    }
    Ok(commentWithSocialUserSerializer.writes(replies))
  }

  def startFollowing(url: String) = AuthenticatedJsonAction { request =>
    CX.withConnection { implicit conn =>
      val uriId = NormalizedURI.getByNormalizedUrl(url).getOrElse(NormalizedURI(url = url).save).id.get
      Follow.get(request.userId, uriId) match {
        case Some(follow) if !follow.isActive => follow.activate.save
        case None => Follow(userId = request.userId, uriId = uriId).save
        case _ => None
      }
    }
    Ok(JsObject(Seq("following" -> JsBoolean(true))))
  }

  def stopFollowing(url: String) = AuthenticatedJsonAction { request =>
    CX.withConnection { implicit conn =>
      NormalizedURI.getByNormalizedUrl(url) match {
        case Some(uri) => Follow.get(request.userId, uri.id.get) match {
          case Some(follow) => follow.deactivate.save
          case None => None
        }
        case None => None
      }
    }
    Ok(JsObject(Seq("following" -> JsBoolean(false))))
  }

  // Given a list of comma separated external user ids, side effects and creates all the necessary recipients
  // For comments with a parent comment, adds recipients to parent comment instead.
  private def createRecipients(commentId: Id[Comment], recipients: String, parentIdOpt: Option[Id[Comment]])(implicit conn: Connection) = {
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
    Comment.getPublic(normalizedURI.id.get)

  private def privateComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    Comment.getPrivate(normalizedURI.id.get, userId)

  private def messageComments(userId: Id[User], normalizedURI: NormalizedURI, includeReplies: Boolean = false)(implicit conn: Connection) =
    Comment.getMessages(normalizedURI.id.get, userId)

  private def notifyRecipientsAsync(comment: Comment) = dispatch({
    comment.permissions match {
      case Comment.Permissions.PUBLIC =>
        CX.withConnection { implicit c =>
          val author = User.get(comment.userId)
          val uri = NormalizedURI.get(comment.uriId)
          val follows = Follow.get(uri.id.get)
          for (userId <- follows.map(_.userId).toSet - comment.userId) {
            val recipient = User.get(userId)
            val addrs = EmailAddress.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              inject[PostOffice].sendMail(ElectronicMail(
                  from = EmailAddresses.SUPPORT, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
                  to = addr, subject = "[new comment] " + uri.title,
                  htmlBody = views.html.email.newComment(author, recipient, uri, comment).body,
                  category = PostOffice.Categories.COMMENT))
            }
          }
        }
      case Comment.Permissions.MESSAGE =>
        CX.withConnection { implicit c =>
          val sender = User.get(comment.userId)
          val uri = NormalizedURI.get(comment.uriId)
          val subjectPrefix = if (comment.parent.isDefined) "[new reply] " else "[new message] "
          val recipients = Comment.getRecipients(comment.parent.getOrElse(comment.id.get))
          for (userId <- recipients.map(_.userId.get).toSet - comment.userId) {
            val recipient = User.get(userId)
            val addrs = EmailAddress.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              inject[PostOffice].sendMail(ElectronicMail(
                  from = EmailAddresses.SUPPORT, fromName = Some("%s %s via Kifi".format(sender.firstName, sender.lastName)),
                  to = addr, subject = subjectPrefix + uri.title.getOrElse(uri.url),
                  htmlBody = views.html.email.newMessage(sender, recipient, uri, comment).body,
                  category = PostOffice.Categories.COMMENT))
            }
          }
        }
      case _ =>
    }
  }, {e => log.error("Could not persist emails for comment %s".format(comment.id.get), e)})

  def followsView = AdminHtmlAction { implicit request =>
    val uriAndUsers = CX.withConnection { implicit c =>
      Follow.all map {f => (toUserWithSocial(User.get(f.userId)), f, NormalizedURI.get(f.uriId))}
    }
    Ok(views.html.follows(uriAndUsers))
  }

  def commentsView = AdminHtmlAction { implicit request =>
    val uriAndUsers = CX.withConnection { implicit c =>
      Comment.all(Comment.Permissions.PUBLIC) map {co => (toUserWithSocial(User.get(co.userId)), co, NormalizedURI.get(co.uriId))}
    }
    Ok(views.html.comments(uriAndUsers))
  }

  def messagesView = AdminHtmlAction { implicit request =>
    val uriAndUsers = CX.withConnection { implicit c =>
      Comment.all(Comment.Permissions.MESSAGE) map {co =>
        (toUserWithSocial(User.get(co.userId)), co, NormalizedURI.get(co.uriId),
            CommentRecipient.getByComment(co.id.get) map { r => toUserWithSocial(User.get(r.userId.get)) })
      }
    }
    Ok(views.html.messages(uriAndUsers))
  }
}
