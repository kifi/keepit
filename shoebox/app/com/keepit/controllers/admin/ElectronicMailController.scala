package com.keepit.controllers.admin

import com.keepit.common.db.slick._
import com.keepit.common.mail._

import com.keepit.common.controller.{AdminController, ActionAuthenticator}

import play.api.Play.current
import views.html

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton}

@Singleton
class ElectronicMailController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  repo: ElectronicMailRepo)
    extends AdminController(actionAuthenticator) {

  def electronicMailsViewFirstPage = electronicMailsView(0)

  def electronicMailsView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, electronicMails) = db.readOnly { implicit s =>
      val electronicMails = repo.page(page, PAGE_SIZE, filterRecipeintNot = EmailAddresses.ENG)
      val count = repo.count(filterRecipeintNot = EmailAddresses.ENG)
      (count, electronicMails)
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(html.admin.electronicMails(electronicMails, page, count, pageCount))
  }
}
