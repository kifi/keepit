package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.realtime.UserNotifier

import views.html

@Singleton
class AdminCommentController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  commentRepo: CommentRepo,
  commentRecipientRepo: CommentRecipientRepo,
  normalizedURIRepo: NormalizedURIRepo,
  followRepo: FollowRepo,
  userRepo: UserRepo,
  userNotifier: UserNotifier)
    extends AdminController(actionAuthenticator) {

  def followsView = AdminHtmlAction { implicit request =>
    val uriAndUsers = db.readOnly { implicit s =>
      followRepo.all() map {f => (userRepo.get(f.userId), f, normalizedURIRepo.get(f.uriId))
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
      (count, (comments map { co => (userRepo.get(co.userId), co, normalizedURIRepo.get(co.uriId)) }))
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
        (userRepo.get(co.userId), co, normalizedURIRepo.get(co.uriId), commentRecipientRepo.getByComment(co.id.get) map { r =>
          userRepo.get(r.userId.get)
        })
      }))
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(html.admin.messages(uriAndUsers, page, count, pageCount))
  }

  def recreateNotificationDetails(safeMode: Boolean) = AdminHtmlAction { request =>
    db.readWrite { implicit session =>
      userNotifier.recreateAllActiveDetails(safeMode)
    }
    Ok
  }
}
