package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import views.html
import play.api.libs.json._
import com.keepit.cortex.CortexServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

class AdminWord2VecController @Inject()(
  cortex: CortexServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator) {

  def wordSimilarity() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val word1 = body.get("word1").get
    val word2 = body.get("word2").get
    val s = Await.result(cortex.word2vecWordSimilarity(word1, word2), 5 seconds)
    val res = s"similarity score for $word1, $word2: " + s.getOrElse("n/a")
    Ok(res)
  }

  def keywords() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val text = body.get("text").get

    val resp = Await.result(cortex.word2vecKeywordsAndBOW(text), 5 seconds)

    val res = s"keywords: ${resp("keywords")}" + "\n\n" +  s"bow: ${resp("bow")}"
    Ok(res.replaceAll("\n","\n<br>"))
  }

  def index() = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.word2vec())
  }

}
