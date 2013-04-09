package com.keepit.controllers.admin

import java.sql.Connection
import scala.Option.option2Iterable
import scala.math.BigDecimal.long2bigDecimal
import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.async.dispatch
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.mail.{ElectronicMail, EmailAddresses, PostOffice}
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.search.graph.URIGraph
import com.keepit.search.index.ArticleIndexer
import com.keepit.serializer.UserWithSocialSerializer.userWithSocialSerializer
import com.keepit.serializer.CommentWithBasicUserSerializer.commentWithBasicUserSerializer
import com.keepit.serializer.ThreadInfoSerializer.threadInfoSerializer
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

@Singleton
class AdminCommentController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  normalizedURIRepo: NormalizedURIRepo,
  userWithSocialRepo: UserWithSocialRepo,
  followRepo: FollowRepo,
  userRepo: UserRepo)
    extends AdminController(actionAuthenticator) {

  def followsView = AdminHtmlAction { implicit request =>
    val uriAndUsers = db.readOnly { implicit s =>
      followRepo.all() map {f =>
        (userWithSocialRepo.toUserWithSocial(userRepo.get(f.userId)), f, normalizedURIRepo.get(f.uriId))
      }
    }
    Ok(html.admin.follows(uriAndUsers))
  }

  def commentsViewFirstPage = commentsView(0)

  def commentsView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, uriAndUsers) = db.readOnly { implicit s =>
      val comments = commentRepo.page(page, PAGE_SIZE)
      val count = commentRepo.count(CommentPermissions.PUBLIC)
      (count, (comments map {
        co => (userWithSocialRepo.toUserWithSocial(userRepo.get(co.userId)), co, normalizedURIRepo.get(co.uriId))
      }))
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(html.admin.comments(uriAndUsers, page, count, pageCount))
  }

  def messagesViewFirstPage =  messagesView(0)

  def messagesView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, uriAndUsers) = db.readOnly { implicit s =>
      val messages = commentRepo.page(page, PAGE_SIZE, CommentPermissions.MESSAGE)
      val count = commentRepo.count(CommentPermissions.MESSAGE)
      (count, (messages map {co =>
        (userWithSocialRepo.toUserWithSocial(userRepo.get(co.userId)), co, normalizedURIRepo.get(co.uriId), commentRecipientRepo.getByComment(co.id.get) map { r =>
          userWithSocialRepo.toUserWithSocial(userRepo.get(r.userId.get))
        })
      }))
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(html.admin.messages(uriAndUsers, page, count, pageCount))
  }
}
