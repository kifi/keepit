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
import com.keepit.model.{Bookmark, Comment, CommentRecipient, EmailAddress, Follow, NormalizedURI, SocialConnection, SocialUserInfo, User, DeepLink, DeepLocator}
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
import com.keepit.common.healthcheck.BabysitterTimeout
import akka.util.duration._

object CommentController extends FortyTwoController {

  // TODO: Remove parameters and only check request body once all installations are 2.1.6 or later.
  def createComment(urlOpt: Option[String],
                    titleOpt: Option[String],
                    textOpt: Option[String],
                    permissionsOpt: Option[String],
                    recipientsOpt: Option[String],
                    parentOpt: Option[String]) = AuthenticatedJsonAction { request =>
    val (url, title, text, permissions, recipients, parent) = request.body.asJson match {
      case Some(o) => (
        (o \ "url").as[String],
        (o \ "title") match { case JsString(s) => s; case _ => ""},
        (o \ "text").as[String].trim,
        (o \ "permissions").as[String],
        (o \ "recipients") match { case JsString(s) => s; case _ => ""},
        (o \ "parent") match { case JsString(s) => s; case _ => ""})
      case _ => (
        urlOpt.get,
        titleOpt.getOrElse(""),
        textOpt.get.trim,
        permissionsOpt.get,
        recipientsOpt.getOrElse(""),
        parentOpt.getOrElse(""))
    }

    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val comment = CX.withConnection { implicit conn =>
      val userId = request.userId
      val uri = NormalizedURI.getByNormalizedUrl(url).getOrElse(NormalizedURI(url = url).save)
      val parentIdOpt = parent match {
        case "" => None
        case id => Comment.get(ExternalId[Comment](id)).id
      }

      permissions.toLowerCase match {
        case "private" =>
          Comment(uriId = uri.id.get, userId = userId, pageTitle = title, text = text, permissions = Comment.Permissions.PRIVATE, parent = parentIdOpt).save
        case "message" =>
          val newComment = Comment(uriId = uri.id.get, userId = userId, pageTitle = title, text = text, permissions = Comment.Permissions.MESSAGE, parent = parentIdOpt).save
          createRecipients(newComment.id.get, recipients, parentIdOpt)
          newComment
        case "public" | "" =>
          Comment(uriId = uri.id.get, userId = userId, pageTitle = title, text = text, permissions = Comment.Permissions.PUBLIC, parent = parentIdOpt).save
        case _ =>
          throw new Exception("Invalid comment permission")
      }
    }

    dispatch(notifyRecipients(comment), {e => log.error("Could not persist emails for comment %s".format(comment.id.get), e)})

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
      NormalizedURI.getByNormalizedUrl(url) map {uri =>
        val uriId = uri.id.get
        val userId = request.userId
        val messageCount = Comment.getMessagesWithChildrenCount(uriId, userId)
        val privateCount = Comment.getPrivateCount(uriId, userId)
        val publicCount = Comment.getPublicCount(uriId)
        (messageCount, privateCount, publicCount)
      } getOrElse (0, 0L, 0L)
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
      val parent = comment.parent map (Comment.get) getOrElse (comment)
      if (true) // TODO: hasPermission(user.id.get, comment.id.get) ???????????????
        (Seq(parent) ++ Comment.getChildren(parent.id.get) map { child => CommentWithSocialUser(child) })
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

  // TODO: Remove parameters and only check request body once all installations are 2.1.6 or later.
  def startFollowing(urlOpt: Option[String]) = AuthenticatedJsonAction { request =>
    val url = urlOpt.getOrElse((request.body.asJson.get \ "url").as[String])
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

  // TODO: Remove parameters and only check request body once all installations are 2.1.6 or later.
  def stopFollowing(urlOpt: Option[String]) = AuthenticatedJsonAction { request =>
    val url = urlOpt.getOrElse((request.body.asJson.get \ "url").as[String])
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

  private[controllers] def notifyRecipients(comment: Comment): Unit = comment.permissions match {
      case Comment.Permissions.PUBLIC =>
        CX.withConnection { implicit c =>
          val author = User.get(comment.userId)
          val uri = NormalizedURI.get(comment.uriId)
          val follows = Follow.get(uri.id.get)
          for (userId <- follows.map(_.userId).toSet - comment.userId) {
            val recipient = User.get(userId)
            val deepLink = DeepLink(
                initatorUserId = Option(comment.userId),
                recipientUserId = Some(userId),
                uriId = Some(comment.uriId),
                deepLocator = DeepLocator.ofComment(comment)).save
            val addrs = EmailAddress.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              inject[PostOffice].sendMail(ElectronicMail(
                  senderUserId = Option(comment.userId),
                  from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
                  to = addr,
                  subject = "%s %s commented on a page you are following".format(author.firstName, author.lastName),
                  htmlBody = replaceLookHereLinks(views.html.email.newComment(author, recipient, deepLink.url, comment).body),
                  category = PostOffice.Categories.COMMENT))
            }
          }
        }
      case Comment.Permissions.MESSAGE =>
        CX.withConnection { implicit c =>
          val senderId = comment.userId
          val sender = User.get(senderId)
          val uri = NormalizedURI.get(comment.uriId)
          val participants = Comment.getParticipantsUserIds(comment)
          for (userId <- participants - senderId) {
            val recipient = User.get(userId)
            val deepLink = DeepLink(
                initatorUserId = Option(comment.userId),
                recipientUserId = Some(userId),
                uriId = Some(comment.uriId),
                deepLocator = DeepLocator.ofMessageThread(comment)).save
            val addrs = EmailAddress.getByUser(userId)
            for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
              inject[PostOffice].sendMail(ElectronicMail(
                  senderUserId = Option(comment.userId),
                  from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(sender.firstName, sender.lastName)),
                  to = addr,
                  subject = "%s %s sent you a message using KiFi".format(sender.firstName, sender.lastName),
                  htmlBody = replaceLookHereLinks(views.html.email.newMessage(sender, recipient, deepLink.url, comment).body),
                  category = PostOffice.Categories.MESSAGE))
            }
          }
        }
      case unsupported =>
        log.error("unsupported comment type for email %s".format(unsupported))
    }

  //e.g. [look here](x-kifi-sel:body>div#page.watch>div:nth-child(4\)>div#watch7-video-container)
  def replaceLookHereLinks(text: String): String =
    """\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)""".r.replaceAllIn(
        text, m => "[" + m.group(1).replaceAll("""\\(.)""", "$1") + "]")

  def followsView = AdminHtmlAction { implicit request =>
    val uriAndUsers = CX.withConnection { implicit c =>
      Follow.all map {f => (toUserWithSocial(User.get(f.userId)), f, NormalizedURI.get(f.uriId))}
    }
    Ok(views.html.follows(uriAndUsers))
  }

  def commentsViewFirstPage = commentsView(0)

  def commentsView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, uriAndUsers) = CX.withConnection { implicit conn =>
      val comments = Comment.page(page, PAGE_SIZE)
      val count = Comment.count(Comment.Permissions.PUBLIC)
      (count, (comments map {co => (toUserWithSocial(User.get(co.userId)), co, NormalizedURI.get(co.uriId))} ))
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.comments(uriAndUsers, page, count, pageCount))
  }

  def messagesViewFirstPage =  messagesView(0)

  def messagesView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, uriAndUsers) = CX.withConnection { implicit conn =>
      val messages = Comment.page(page, PAGE_SIZE, Comment.Permissions.MESSAGE)
      val count = Comment.count(Comment.Permissions.MESSAGE)
      (count, (messages map {co => (toUserWithSocial(User.get(co.userId)), co, NormalizedURI.get(co.uriId), CommentRecipient.getByComment(co.id.get) map { r => toUserWithSocial(User.get(r.userId.get)) }) } ))
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.messages(uriAndUsers, page, count, pageCount))
  }
}
