package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ActionAuthenticator, AdminController}
import com.keepit.cortex.CortexServiceClient
import com.keepit.shoebox.ShoeboxServiceClient

import views.html
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration._


class AdminLDAController @Inject()(
  cortex: CortexServiceClient,
  shoebox: ShoeboxServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator) {

  def index() = AdminHtmlAction.authenticated { implicit request =>
    val n = Await.result(cortex.ldaNumOfTopics, 5 seconds)
    Ok(html.admin.lda(n))
  }

  def showTopics() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val fromId = body.get("fromId").get.toInt
    val toId = body.get("toId").get.toInt
    val topN = body.get("topN").get.toInt
    val res = Await.result(cortex.ldaShowTopics(fromId, toId, topN), 5 seconds)
    Ok(Json.toJson(res))
  }
}
