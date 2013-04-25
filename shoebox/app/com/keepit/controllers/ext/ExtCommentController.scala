package com.keepit.controllers.ext

import java.sql.Connection
import scala.Option.option2Iterable
import scala.math.BigDecimal.long2bigDecimal
import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.mail.{ElectronicMail, EmailAddresses, PostOffice}
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.serializer.CommentWithBasicUserSerializer.commentWithBasicUserSerializer
import play.api.http.ContentTypes
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial
import securesocial.core.java.SecureSocial.SecuredAction
import com.keepit.common.social.ThreadInfo
import com.keepit.common.healthcheck.BabysitterTimeout
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.analytics.ActivityStream
import views.html
import com.keepit.realtime.UserNotifier
import scala.concurrent.future
import scala.concurrent.ExecutionContext.Implicits.global

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
  postOffice: PostOffice,
  activityStream: ActivityStream,
  userNotifier: UserNotifier)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getCounts(ids: String) = AuthenticatedJsonAction { request =>
    val nUriExtIds = ids.split('.').map(ExternalId[NormalizedURI](_))
    val counts = db.readOnly { implicit s =>
      nUriExtIds.map { extId =>
        val id = normalizedURIRepo.get(extId).id.get
        extId -> (commentRepo.getPublicCount(id), commentRepo.getParentMessages(id, request.userId).size)
      }
    }
    Ok(JsObject(counts.map { case (id, n) => id.id -> JsArray(Seq(JsNumber(n._1), JsNumber(n._2))) }))
  }
  
  def postCommentAction() = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val (urlStr, title, text) = (
      (o \ "url").as[String],
      (o \ "title") match { case JsString(s) => s; case _ => ""},
      (o \ "text").as[String].trim)

    val comment = postComment(request.user.id.get, urlStr, title, text)

    Ok(Json.obj("commentId" -> comment.externalId.id, "createdAt" -> JsString(comment.createdAt.toString)))
  }
  
  private[ext] def postComment(userId: Id[User], urlStr: String, title: String, text: String): Comment = {
    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val comment = db.readWrite {implicit s =>
      val uri = normalizedURIRepo.save(normalizedURIRepo.getByNormalizedUrl(urlStr).getOrElse(NormalizedURIFactory(url = urlStr)))

      val url: URL = urlRepo.save(urlRepo.get(urlStr).getOrElse(URLFactory(url = urlStr, normalizedUriId = uri.id.get)))

      val newComment = commentRepo.save(Comment(uriId = uri.id.get, urlId = url.id, userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.PUBLIC, parent = None))
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
      log.error("Could not persist emails for comment %s".format(comment.id.get), e)
    }
    
    comment
  }
  
  def sendMessageAction() = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val (urlStr, title, text, recipients) = (
      (o \ "url").as[String],
      (o \ "title") match { case JsString(s) => s; case _ => ""},
      (o \ "text").as[String].trim,
      (o \ "recipients") match { case JsString(s) => s; case _ => ""})
      
    val newMessage = sendMessage(request.user.id.get, urlStr, title, text, recipients)
    
    val message = db.readOnly(implicit s => commentWithBasicUserRepo.load(newMessage))
    Ok(Json.obj("message" -> commentWithBasicUserSerializer.writes(message)))
  }
  
  private[ext] def sendMessage(userId: Id[User], urlStr: String, title: String, text: String, recipients: String): Comment = {
    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val (uri, url, recipientUserIds, existingParentOpt) = db.readWrite { implicit s =>
      val uri = normalizedURIRepo.save(normalizedURIRepo.getByNormalizedUrl(urlStr).getOrElse(NormalizedURIFactory(url = urlStr)))

      val url: URL = urlRepo.save(urlRepo.get(urlStr).getOrElse(URLFactory(url = urlStr, normalizedUriId = uri.id.get)))

      val recipientUserIds = recipients.split(",").map{id => userRepo.get(ExternalId[User](id)).id.get}.toSet
      val existingParentOpt = commentRepo.getParentByUriParticipants(uri.id.get, recipientUserIds + userId)
      
      (uri, url, recipientUserIds, existingParentOpt)
    }
      
    existingParentOpt match {
      case Some(parentId) =>
        sendMessageReply(userId, urlStr, title, text, parentId)
      case None =>
        db.readWrite { implicit s =>
          val message = commentRepo.save(Comment(
            uriId = uri.id.get,
            urlId = url.id,
            userId = userId,
            pageTitle = title,
            text = LargeString(text),
            permissions = CommentPermissions.MESSAGE,
            parent = None)
          )
          recipientUserIds foreach { userId =>
            commentRecipientRepo.save(CommentRecipient(commentId = message.id.get, userId = Some(userId)))
          }
          
          val newCommentRead = commentReadRepo.getByUserAndParent(userId, message.id.get) match {
            case Some(commentRead) => // existing CommentRead entry for this message thread
              assert(commentRead.parentId.isDefined)
              commentRead.withLastReadId(message.id.get)
            case None =>
              CommentRead(userId = userId, uriId = uri.id.get, parentId = Some(message.id.get), lastReadId = message.id.get)
          }
          commentReadRepo.save(newCommentRead)
    
          future {
            notifyRecipients(message)
          } onFailure { case e =>
            log.error("Could not persist emails for comment %s".format(message.id.get), e)
          }
          
          message
        }
        
    }
  }
  
  def sendMessageReplyAction(parentExtId: ExternalId[Comment]) = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val (urlStr, title, text) = (
      (o \ "url").as[String],
      (o \ "title") match { case JsString(s) => s; case _ => ""},
      (o \ "text").as[String].trim
    )
    
    val parentId = db.readOnly(commentRepo.get(parentExtId)(_)).id.get
      
    val newMessage = sendMessageReply(request.user.id.get, urlStr, title, text, parentId)
    
    val message = db.readOnly(implicit s => commentWithBasicUserRepo.load(newMessage))
    Ok(Json.obj("message" -> commentWithBasicUserSerializer.writes(message)))
  }
  
  private[ext] def sendMessageReply(userId: Id[User], urlStr: String, title: String, text: String, parentId: Id[Comment]): Comment = {

    if (text.isEmpty) throw new Exception("Empty comments are not allowed")
    val messageReply = db.readWrite {implicit s =>
      val uri = normalizedURIRepo.save(normalizedURIRepo.getByNormalizedUrl(urlStr).getOrElse(NormalizedURIFactory(url = urlStr)))

      val url: URL = urlRepo.save(urlRepo.get(urlStr).getOrElse(URLFactory(url = urlStr, normalizedUriId = uri.id.get)))

      val parent = commentRepo.get(parentId)
      val realParentId = parent.parent.getOrElse(parent.id.get)
      
      val newMessageReply = commentRepo.save(Comment(
        uriId = uri.id.get,
        urlId = url.id,
        userId = userId,
        pageTitle = title,
        text = LargeString(text),
        permissions = CommentPermissions.MESSAGE,
        parent = Some(realParentId))
      )

      val newCommentRead = commentReadRepo.getByUserAndParent(userId, realParentId) match {
        case Some(commentRead) => // existing CommentRead entry for this message thread
          assert(commentRead.parentId.isDefined)
          commentRead.withLastReadId(newMessageReply.id.get)
        case None =>
          CommentRead(userId = userId, uriId = uri.id.get, parentId = Some(realParentId), lastReadId = newMessageReply.id.get)
      }
      commentReadRepo.save(newCommentRead)
      newMessageReply
    }

    future {
      notifyRecipients(messageReply)
    } onFailure { case e =>
      log.error("Could not persist emails for comment %s".format(messageReply.id.get), e)
    }
    
    messageReply
  }
  
  

  /* depricated, remove after all clients are updated to 2.3.24 */
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
      val uri = normalizedURIRepo.save(normalizedURIRepo.getByNormalizedUrl(urlStr).getOrElse(NormalizedURIFactory(url = urlStr)))

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
    } onFailure { case e =>
      log.error("Could not persist emails for comment %s".format(comment.id.get), e)
    }

    comment.permissions match {
      case CommentPermissions.MESSAGE =>
        val message = db.readOnly(implicit s => commentWithBasicUserRepo.load(comment))
        Ok(Json.obj("message" -> commentWithBasicUserSerializer.writes(message)))
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
      val uriId = normalizedURIRepo.getByNormalizedUrl(url).getOrElse(normalizedURIRepo.save(NormalizedURIFactory(url = url))).id.get
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
      normalizedURIRepo.getByNormalizedUrl(url).map { uri =>
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
