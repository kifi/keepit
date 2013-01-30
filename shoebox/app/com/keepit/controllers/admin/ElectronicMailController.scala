package com.keepit.controllers.admin

import com.keepit.common.db.CX
import play.api.Play.current
import com.keepit.common.mail._

import com.keepit.common.controller.FortyTwoController

object ElectronicMailController extends FortyTwoController {

  def electronicMailsViewFirstPage = electronicMailsView(0)

  def electronicMailsView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, electronicMails) = CX.withConnection { implicit conn =>
      val electronicMails = ElectronicMailCx.page(page, PAGE_SIZE, filterRecipeintNot = EmailAddresses.ENG)
      val count = ElectronicMailCx.count(filterRecipeintNot = EmailAddresses.ENG)
      (count, electronicMails)
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.electronicMails(electronicMails, page, count, pageCount))
  }
}
