package com.keepit.controllers.admin

import com.keepit.search.SearchServiceClient
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions, AdminController }
import com.google.inject.Inject
import scala.concurrent.Await
import scala.concurrent.duration._
import views.html

class AdminSpellCorrectorController @Inject() (
    searchClient: SearchServiceClient,
    val userActionsHelper: UserActionsHelper) extends AdminUserActions {

  val Home = Redirect(routes.AdminSpellCorrectorController.spellChecker())

  def correct() = AdminUserPage { request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val query = body.get("query").get
    val t1 = System.currentTimeMillis
    val suggest = Await.result(searchClient.correctSpelling(query, enableBoost = true), 5 seconds)
    val t2 = System.currentTimeMillis
    val message = s"time elpased: ${(t2 - t1) / 1000.0} seconds\ninput: ${query}, suggestions: \n${suggest}"
    Home.flashing("success" -> message)
  }

  def spellChecker() = AdminUserPage { implicit request =>
    Ok(html.admin.spellchecker())
  }
}
