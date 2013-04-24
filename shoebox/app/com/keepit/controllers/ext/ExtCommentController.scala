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
          val recipientUserIds = recipientUsers(recipients).map(_.id.get)
          val parentIdOpt = parent match {
            case "" => commentRepo.getParentByUriRecipients(uri.id.get, recipientUserIds + userId)
            case id =>
              val parent = commentRepo.get(ExternalId[Comment](id))
              Some(parent.parent.getOrElse(parent.id.get))
          }
          val newComment = commentRepo.save(Comment(uriId = uri.id.get, urlId = url.id,  userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.MESSAGE, parent = parentIdOpt))
          if(parentIdOpt.isEmpty)
            createCommentRecipients(newComment.id.get, recipientUserIds)

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
          val newComment = commentRepo.save(Comment(uriId = uri.id.get, urlId = url.id, userId = userId, pageTitle = title, text = LargeString(text), permissions = CommentPermissions.PUBLIC, parent = None))
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

    addToActivityStream(comment)

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

  private def createCommentRecipients(parentId: Id[Comment], recipients: Set[Id[User]])(implicit session: RWSession) = {
    recipients map { recipientUser =>
      Some(commentRecipientRepo.save(CommentRecipient(commentId = parentId, userId = Some(recipientUser))))
    }
  }
  
  private def recipientUsers(recipientString: String)(implicit session: RSession) = {
    (recipientString.split(",").map(_.trim()).map { recipientId =>
      userRepo.getOpt(ExternalId[User](recipientId))
    } flatten).toSet
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

  private def addToActivityStream(comment: Comment) = {
    val (user, social, uri) = db.readOnly { implicit session =>
      val user = userRepo.get(comment.userId)
      val social = socialUserInfoRepo.getByUser(user.id.get).headOption.map(_.socialId.id).getOrElse("")
      val uri = normalizedURIRepo.get(comment.uriId)

      (user, social, uri)
    }

    val json = Json.obj(
      "user" -> Json.obj(
        "id" -> user.id.get.id,
        "name" -> s"${user.firstName} ${user.lastName}",
        "avatar" -> s"https://graph.facebook.com/${social}/picture?height=150&width=150"),
      "comment" -> Json.obj(
        "id" -> comment.id.get.id,
        "text" -> comment.text.toString,
        "title" -> comment.pageTitle,
        "uri" -> uri.url)
    )

    val kind = comment.permissions match {
      case CommentPermissions.PUBLIC => "comment"
      case CommentPermissions.MESSAGE => "message"
    }

    activityStream.streamActivity(kind, json)
  }
}
