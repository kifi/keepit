package com.keepit.controllers.admin

import com.keepit.search.SearchServiceClient
import com.keepit.common.controller.{ActionAuthenticator, AdminController}
import com.google.inject.Inject
import scala.concurrent.Await
import scala.concurrent.duration._
import views.html

class AdminSpellCorrectorController @Inject() (
  searchClient: SearchServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator) {
  def suggest(input: String, enableBoost: Boolean = true) = AdminHtmlAction { request =>
    val t1 = System.currentTimeMillis
    val suggest = Await.result(searchClient.correctSpelling(input, enableBoost), 5 seconds)
    val t2 = System.currentTimeMillis
    Ok(s"time elpased: ${(t2 - t1)/1000.0} seconds\ninput: ${input}, suggestion: \n${suggest}")
  }

  val Home = Redirect(routes.AdminSpellCorrectorController.spellChecker())

  def correct() = AdminHtmlAction { request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val query = body.get("query").get
    val t1 = System.currentTimeMillis
    val suggest = Await.result(searchClient.correctSpelling(query, enableBoost = true), 5 seconds)
    val t2 = System.currentTimeMillis
    Home.flashing("success" -> suggest)
  }

  def spellChecker() = AdminHtmlAction { implicit request =>
    Ok(html.admin.spellchecker())
  }
}
