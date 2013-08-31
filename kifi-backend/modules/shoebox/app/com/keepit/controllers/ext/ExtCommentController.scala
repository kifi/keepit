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
          val message = Comment(
            id = Some(Id[Comment](-1)),
            uriId = uri.id.get,
            urlId = url.id,
            userId = userId,
            pageTitle = title,
            text = LargeString(text),
            permissions = CommentPermissions.MESSAGE,
            parent = None)
          // recipientUserIds foreach { userId =>
          //   commentRecipientRepo.save(CommentRecipient(commentId = message.id.get, userId = Some(userId)))
          // }
          // commentReadRepo.save(
          //   CommentRead(userId = userId, uriId = uri.id.get, parentId = Some(message.id.get), lastReadId = message.id.get))
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

  private[ext] def sendMessageReply(userId: Id[User], text: String, parentId: Either[Id[Comment], ExternalId[Comment]]): (Comment, Comment) = {
    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val message = db.readWrite { implicit s =>
      val extId = parentId match {
        case Right(extId) => extId
        case _ => ExternalId[Comment]()
      }
      // val parent = reqParent.parent.map(commentRepo.get).getOrElse(reqParent)
      val message = Comment(
        id = Some(Id[Comment](-1)),
        externalId = extId,
        uriId = Id[NormalizedURI](1),
        urlId = None,
        userId = userId,
        pageTitle = "",
        text = LargeString(text),
        permissions = CommentPermissions.MESSAGE,
        parent = None)
      message
    }

    // db.readWrite(attempts = 2) { implicit s =>
    //   commentReadRepo.save(commentReadRepo.getByUserAndParent(userId, parent.id.get) match {
    //     case Some(commentRead) =>
    //       commentRead.withLastReadId(message.id.get)
    //     case None =>
    //       CommentRead(userId = userId, uriId = parent.uriId, parentId = parent.id, lastReadId = message.id.get)
    //   })
    // }

    future {  // important that this is spawned only *after* above read/write transaction committed
      notifyRecipients(message)
    }(RealtimeUserFacingExecutionContext.ec) onFailure { case e =>
      log.error("Could not notify for message reply %s".format(message.id.get), e)
    }

    (message, message)
  }

  private def getOrCreateUriAndUrl(urlStr: String): (NormalizedURI, URL) = {
    db.readWrite(attempts = 2) { implicit s =>
      val uri = normalizedURIRepo.internByUri(urlStr)
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
