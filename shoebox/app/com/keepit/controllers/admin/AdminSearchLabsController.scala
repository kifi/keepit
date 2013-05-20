package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.search._
import play.api.libs.json.JsObject
import scala.concurrent.ExecutionContext.Implicits.global
import views.html

@Singleton
class AdminSearchLabsController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  searchConfigManager: SearchConfigManager,
  normalizedURIRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  searchClient: SearchServiceClient)
    extends AdminController(actionAuthenticator) {

  def friendMap(q: Option[String] = None, minKeeps: Option[Int] = None) = AdminHtmlAction { implicit request =>
    Ok(html.labs.friendMap(q, minKeeps))
  }
  def friendMapJson(q: Option[String] = None, minKeeps: Option[Int]) = AdminJsonAction { implicit request =>
    Async {
      searchClient.friendMapJson(request.userId, q, minKeeps).map { json => Ok(JsObject(Seq("data" -> json))) }
    }
  }
}
