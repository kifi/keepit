package com.keepit.controllers.admin

import com.google.inject.{ Inject, Singleton }
import play.api.Play.current
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.search.SearchServiceClient
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.concurrent.Akka
import play.api.libs.json.Json

@Singleton
class AdminSpellCorrectorController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchClient: SearchServiceClient) extends AdminController(actionAuthenticator) {
  def buildDictionary() = AdminHtmlAction { implicit request =>
    Akka.future {
      searchClient.buildSpellCorrectorDictionary()
    }
    Redirect(routes.AdminSpellCorrectorController.spellController())
  }

  def correctSpelling(text: String) = AdminJsonAction{ implicit request =>
    Async {
      searchClient.correctSpelling(text).map( r => Ok(Json.obj("correction" -> r )))
    }
  }

  def spellController() = AdminHtmlAction { implicit request =>
    Async {
      val response = searchClient.getSpellCorrectorStatus
      response.map(r => Ok(views.html.admin.spellCorrector(r)))
    }
  }
}
