package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.search._
import play.api.libs.json.JsObject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import views.html

class AdminSearchLabsController @Inject() (actionAuthenticator: ActionAuthenticator, searchClient: SearchServiceClient)
    extends AdminController(actionAuthenticator) {

  def friendMap(q: Option[String] = None, minKeeps: Option[Int] = None) = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.labs.friendMap(q, minKeeps))
  }

  def friendMapJson(q: Option[String] = None, minKeeps: Option[Int]) = AdminJsonAction.authenticatedAsync { implicit request =>
    searchClient.friendMapJson(request.userId, q, minKeeps).map { json => Ok(JsObject(Seq("data" -> json))) }
  }
}
