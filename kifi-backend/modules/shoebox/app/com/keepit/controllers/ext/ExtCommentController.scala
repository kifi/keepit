package com.keepit.controllers.ext

import scala.concurrent.future

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.realtime.UserNotifier

import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import com.keepit.common.akka.RealtimeUserFacingExecutionContext

class ExtCommentController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  normalizedURIRepo: NormalizedURIRepo,
  commentReadRepo: CommentReadRepo,
  urlRepo: URLRepo,
  userRepo: UserRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  commentWithBasicUserRepo: CommentWithBasicUserRepo,
  followRepo: FollowRepo,
  emailAddressRepo: EmailAddressRepo,
  deepLinkRepo: DeepLinkRepo,
  userNotifier: UserNotifier)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getCounts = AuthenticatedJsonToJsonAction { request =>
    val urls = request.body.as[Seq[String]]
    val counts = db.readOnly { implicit s =>
      urls.flatMap(normalizedURIRepo.getByUri).map { nUri =>
        nUri.url -> (commentRepo.getPublicCount(nUri.id.get), commentRepo.getParentMessages(nUri.id.get, request.userId).size)
      }
    }
    Ok(JsObject(counts.map { case (url, n) => url -> JsArray(Seq(JsNumber(n._1), JsNumber(n._2))) }))
  }
  
  def sendMessageAction() = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val (urlStr, title, text, recipients) = (
      (o \ "url").as[String],
      (o \ "title").as[String],
      (o \ "text").as[String].trim,
      (o \ "recipients").as[Seq[String]])

    val (message, parentOpt) = sendMessage(request.user.id.get, urlStr, title, text, recipients)

    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> parentOpt.map(_.externalId.id), "createdAt" -> message.createdAt))
  }

  private[ext] def sendMessage(userId: Id[User], urlStr: String, title: String, text: String, recipients: Seq[String]): (Comment, Option[Comment]) = {
    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val (uri, url) = getOrCreateUriAndUrl(urlStr)
    val (recipientUserIds, parentIdOpt) = db.readWrite { implicit s =>
      val recipientUserIds = recipients.map{id => userRepo.get(ExternalId[User](id)).id.get}.toSet
      val parentIdOpt = commentRepo.getParentByUriParticipants(uri.id.get, recipientUserIds + userId)
      (recipientUserIds, parentIdOpt)
    }

    parentIdOpt match {
      case Some(parentId) =>
        val (message, parent) = sendMessageReply(userId, text, Left(parentId))
        (message, Some(parent))
      case None =>
        val message = db.readWrite { implicit s =>
          val message = commentRepo.save(Comment(
            uriId = uri.id.get,
            urlId = url.id,
            userId = userId,
            pageTitle = title,
            text = LargeString(text),
            permissions = CommentPermissions.MESSAGE,
            parent = None))
          recipientUserIds foreach { userId =>
            commentRecipientRepo.save(CommentRecipient(commentId = message.id.get, userId = Some(userId)))
          }
          commentReadRepo.save(
            CommentRead(userId = userId, uriId = uri.id.get, parentId = Some(message.id.get), lastReadId = message.id.get))
          message
        }
        future {  // important that this is spawned only *after* above read/write transaction committed
          notifyRecipients(message)
        }(RealtimeUserFacingExecutionContext.ec) onFailure { case e =>
          log.error("Could not notify for new message %s".format(message.id.get), e)
        }
        (message, None)
    }
  }

  def sendMessageReplyAction(parentExtId: ExternalId[Comment]) = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val text = (o \ "text").as[String].trim

    val (message, parent) = sendMessageReply(request.user.id.get, text, Right(parentExtId))

    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> parent.externalId.id, "createdAt" -> message.createdAt))
  }

  private[ext] def sendMessageReply(userId: Id[User], text: String, parentId: Either[Id[Comment], ExternalId[Comment]]): (Comment, Comment) = {
    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val (message, parent) = db.readWrite { implicit s =>
      val reqParent = parentId match {
        case Left(id) => commentRepo.get(id)
        case Right(extId) => commentRepo.get(extId)
      }
      val parent = reqParent.parent.map(commentRepo.get).getOrElse(reqParent)
      val message = commentRepo.save(Comment(
        uriId = parent.uriId,
        urlId = parent.urlId,
        userId = userId,
        pageTitle = parent.pageTitle,
        text = LargeString(text),
        permissions = CommentPermissions.MESSAGE,
        parent = parent.id))
      (message, parent)
    }

    db.readWrite(attempts = 2) { implicit s =>
      commentReadRepo.save(commentReadRepo.getByUserAndParent(userId, parent.id.get) match {
        case Some(commentRead) =>
          commentRead.withLastReadId(message.id.get)
        case None =>
          CommentRead(userId = userId, uriId = parent.uriId, parentId = parent.id, lastReadId = message.id.get)
      })
    }

    future {  // important that this is spawned only *after* above read/write transaction committed
      notifyRecipients(message)
    }(RealtimeUserFacingExecutionContext.ec) onFailure { case e =>
      log.error("Could not notify for message reply %s".format(message.id.get), e)
    }

    (message, parent)
  }

  private def getOrCreateUriAndUrl(urlStr: String): (NormalizedURI, URL) = {
    db.readWrite(attempts = 2) { implicit s =>
      val uri = normalizedURIRepo.getByUri(urlStr).getOrElse(normalizedURIRepo.save(NormalizedURIFactory(url = urlStr)))
      val url: URL = urlRepo.get(urlStr).getOrElse(urlRepo.save(URLFactory(url = urlStr, normalizedUriId = uri.id.get)))
      (uri, url)
    }
  }


  private[controllers] def notifyRecipients(comment: Comment): Unit = {
    comment.permissions match {
      case CommentPermissions.PUBLIC =>
        userNotifier.comment(comment)
      case CommentPermissions.MESSAGE =>
        userNotifier.message(comment)
      case unsupported =>
        log.error("unsupported comment type for email %s".format(unsupported))
    }
  }
}
