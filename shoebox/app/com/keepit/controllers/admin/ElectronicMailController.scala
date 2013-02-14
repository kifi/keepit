package com.keepit.controllers.admin

import com.keepit.inject._
import com.keepit.common.db.slick._
import com.keepit.common.mail._

import com.keepit.common.controller.FortyTwoController

import play.api.Play.current

object ElectronicMailController extends FortyTwoController {

  def electronicMailsViewFirstPage = electronicMailsView(0)

  def electronicMailsView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, electronicMails) = inject[DBConnection].readOnly { implicit s =>
      val repo = inject[ElectronicMailRepo]
      val electronicMails = repo.page(page, PAGE_SIZE, filterRecipeintNot = EmailAddresses.ENG)
      val count = repo.count(filterRecipeintNot = EmailAddresses.ENG)
      (count, electronicMails)
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.electronicMails(electronicMails, page, count, pageCount))
  }
}
