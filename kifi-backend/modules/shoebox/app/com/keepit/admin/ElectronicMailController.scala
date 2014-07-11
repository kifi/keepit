package com.keepit.controllers.admin

import com.keepit.common.db.slick._
import com.keepit.common.mail._

import com.keepit.common.controller.{ AdminController, ActionAuthenticator }

import play.api.Play.current
import views.html

import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.google.inject.Inject
import play.api.mvc.Action

class ElectronicMailController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  repo: ElectronicMailRepo)
    extends AdminController(actionAuthenticator) {

  def electronicMailsViewFirstPage = electronicMailsView(0)

  def electronicMailsView(page: Int = 0) = AdminHtmlAction.authenticated { request =>
    val PAGE_SIZE = 200
    val (count, electronicMails) = db.readOnlyReplica { implicit s =>
      val electronicMails = repo.page(page, PAGE_SIZE)
      val count = repo.count(s)
      (count, electronicMails)
    }
    val pageCount: Int = count / PAGE_SIZE + 1
    Ok(html.admin.electronicMails(electronicMails, page, count, pageCount))
  }
}
