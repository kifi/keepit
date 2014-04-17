package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import views.html
import play.api.libs.json._

class AdminWord2VecController @Inject()(
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator) {

  def wordSimilarity() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val word1 = body.get("word1").get
    val word2 = body.get("word2").get
    val res = s"fake similarity score for $word1, $word2: 0.5"
    Ok(res)
  }

  def keywords() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val text = body.get("text").get
    val res = s"keywords: fake keywords for ${text}" + "\n\n" +  s"bow: fake bow"
    Ok(res.replaceAll("\n","\n<br>"))
  }

  def index() = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.word2vec())
  }

}
