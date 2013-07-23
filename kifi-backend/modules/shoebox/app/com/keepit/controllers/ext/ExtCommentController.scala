package com.keepit.controllers.ext

import scala.concurrent.future

import com.google.inject.{Inject, Singleton}
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

@Singleton
class ExtCommentController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  normalizedURIRepo: NormalizedURIRepo,
  commentReadRepo: CommentReadRepo,
  urlRepo: URLRepo,
  userRepo: UserRepo,
  userExperimentRepo: UserExperimentRepo,
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

  def postCommentAction() = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val (urlStr, title, text) = (
      (o \ "url").as[String],
      (o \ "title").as[String],
      (o \ "text").as[String].trim)

    val comment = postComment(request.user.id.get, urlStr, title, text)

    Ok(Json.obj("id" -> comment.externalId.id, "createdAt" -> JsString(comment.createdAt.toString)))
  }

  private[ext] def postComment(userId: Id[User], urlStr: String, title: String, text: String): Comment = {
    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val (uri, url) = getOrCreateUriAndUrl(urlStr)
    val comment = db.readWrite { implicit s =>
      val newComment = commentRepo.save(
          Comment(uriId = uri.id.get, urlId = url.id, userId = userId,
              pageTitle = title, text = LargeString(text),
              permissions = CommentPermissions.PUBLIC, parent = None))
      commentReadRepo.save(commentReadRepo.getByUserAndUri(userId, uri.id.get) match {
        case Some(commentRead) => // existing CommentRead entry for this message thread
          commentRead.withLastReadId(newComment.id.get)
        case None =>
          CommentRead(userId = userId, uriId = uri.id.get, lastReadId = newComment.id.get)
      })
      newComment
    }

    future {
      userNotifier.comment(comment)
    } onFailure { case e =>
      log.error("Could not notify users for comment %s".format(comment.id.get), e)
    }

    comment
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

  /* deprecated, remove after all clients are updated to 2.3.24 */
  def createComment() = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val (urlStr, title, text, permissions, recipients, parent) = (
      (o \ "url").as[String],
      (o \ "title") match { case JsString(s) => s; case _ => ""},
      (o \ "text").as[String].trim,
      (o \ "permissions").as[String],
      (o \ "recipients") match { case JsString(s) => s; case _ => ""},
      (o \ "parent") match { case JsString(s) => s; case _ => ""})

    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val comment = db.readWrite {implicit s =>
      val userId = request.userId
      val uri = normalizedURIRepo.save(normalizedURIRepo.getByUri(urlStr).getOrElse(NormalizedURIFactory(url = urlStr)))

      val url: URL = urlRepo.save(urlRepo.get(urlStr).getOrElse(URLFactory(url = urlStr, normalizedUriId = uri.id.get)))

      permissions.toLowerCase match {
        case "message" =>
          val (parentIdOpt, recipientUserIds) = parent match {
            case "" =>
              val recipientUserIds = recipients.split(",").map{id => userRepo.get(ExternalId[User](id)).id.get}.toSet
              val parentIdOpt = commentRepo.getParentByUriParticipants(uri.id.get, recipientUserIds + userId)
              (parentIdOpt, recipientUserIds)
            case id =>
              val parent = commentRepo.get(ExternalId[Comment](id))
              (Some(parent.parent.getOrElse(parent.id.get)), Nil)
          }
          val newComment = commentRepo.save(Comment(
            uriId = uri.id.get,
            urlId = url.id,
            userId = userId,
            pageTitle = title,
            text = LargeString(text),
            permissions = CommentPermissions.MESSAGE,
            parent = parentIdOpt))
          if (parentIdOpt.isEmpty) {
            recipientUserIds foreach { userId =>
              commentRecipientRepo.save(CommentRecipient(commentId = newComment.id.get, userId = Some(userId)))
            }
          }
          val newCommentRead = commentReadRepo.getByUserAndParent(userId, parentIdOpt.getOrElse(newComment.id.get)) match {
            case Some(commentRead) => // existing CommentRead entry for this message thread
              assert(commentRead.parentId.isDefined)
              commentRead.withLastReadId(newComment.id.get)
            case None =>
              CommentRead(userId = userId, uriId = uri.id.get, parentId = Some(parentIdOpt.getOrElse(newComment.id.get)), lastReadId = newComment.id.get)
          }
          commentReadRepo.save(newCommentRead)
          newComment
        case "public" | "" =>
          val newComment = commentRepo.save(Comment(
            uriId = uri.id.get,
            urlId = url.id,
            userId = userId,
            pageTitle = title,
            text = LargeString(text),
            permissions = CommentPermissions.PUBLIC,
            parent = None))
          commentReadRepo.save(commentReadRepo.getByUserAndUri(userId, uri.id.get) match {
            case Some(commentRead) => // existing CommentRead entry for this message thread
              commentRead.withLastReadId(newComment.id.get)
            case None =>
              CommentRead(userId = userId, uriId = uri.id.get, lastReadId = newComment.id.get)
          })
          newComment
        case _ =>
          throw new Exception("Invalid comment permission")
      }
    }

    future {
      notifyRecipients(comment)
    }(RealtimeUserFacingExecutionContext.ec) onFailure { case e =>
      log.error("Could not persist emails for comment %s".format(comment.id.get), e)
    }

    comment.permissions match {
      case CommentPermissions.MESSAGE =>
        val message = db.readOnly(implicit s => commentWithBasicUserRepo.load(comment))
        Ok(Json.obj("message" -> Json.toJson(message)))
      case _ =>
        Ok(Json.obj("commentId" -> comment.externalId.id, "createdAt" -> JsString(comment.createdAt.toString)))
    }
  }

  def removeComment(id: ExternalId[Comment]) = AuthenticatedJsonAction { request =>
    db.readWrite { implicit s =>
      if (userExperimentRepo.hasExperiment(request.userId, ExperimentTypes.ADMIN)) {
        commentRepo.getOpt(id).filter(_.isActive).map { c =>
          commentRepo.save(c.withState(CommentStates.INACTIVE))
        }
        Ok(Json.obj("state" -> "INACTIVE"))
      } else {
        Unauthorized("ADMIN")
      }
    }
  }

  def startFollowing() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    db.readWrite { implicit session =>
      val uriId = normalizedURIRepo.getByUri(url).getOrElse(normalizedURIRepo.save(NormalizedURIFactory(url = url))).id.get
      followRepo.get(request.userId, uriId, excludeState = None) match {
        case Some(follow) if !follow.isActive =>
          Some(followRepo.save(follow.activate))
        case None =>
          val urlId = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uriId))).id
          Some(followRepo.save(Follow(userId = request.userId, urlId = urlId, uriId = uriId)))
        case _ => None
      }
    }

    Ok(JsObject(Seq("following" -> JsBoolean(true))))
  }

  def stopFollowing() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    db.readWrite { implicit session =>
      normalizedURIRepo.getByUri(url).map { uri =>
        followRepo.get(request.userId, uri.id.get).map { follow =>
          followRepo.save(follow.deactivate)
        }
      }
    }

    Ok(JsObject(Seq("following" -> JsBoolean(false))))
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
