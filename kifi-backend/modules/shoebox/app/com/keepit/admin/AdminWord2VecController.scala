package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import views.html
import play.api.libs.json._
import com.keepit.cortex.CortexServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.model.User

class AdminWord2VecController @Inject()(
  cortex: CortexServiceClient,
  shoebox: ShoeboxServiceClient,
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

    val resp = Await.result(cortex.word2vecKeywordsAndBOW(text), 10 seconds)

    val res = s"keywords(in the sense of cosine similarity): ${resp("keywords")}" + "\n\n" +  s"bow: ${resp("bow")}"
    Ok(res.replaceAll("\n","\n<br>"))
  }

  def uriSimilarity() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val uri1 = body.get("uri1").get.toLong
    val uri2 = body.get("uri2").get.toLong
    val s = Await.result(cortex.word2vecURISimilairty(Id[NormalizedURI](uri1), Id[NormalizedURI](uri2)), 5 seconds)

    val res = s match {
      case Some(score) => s"similarity score for $uri1, $uri2: ${score}"
      case None => s"one of the uri doesn't have a feature vector. Bad content maybe?"
    }
    Ok(res)
  }

  def userSimilarity() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val user1 = body.get("user1").get.toLong
    val user2 = body.get("user2").get.toLong
    val user1Keeps = shoebox.getBookmarks(Id[User](user1))
    val user2Keeps = shoebox.getBookmarks(Id[User](user2))
    val user1uris = Await.result(user1Keeps, 5 seconds).take(50).map{_.uriId}
    val user2uris = Await.result(user2Keeps, 5 seconds).take(50).map{_.uriId}
    val sfuture = cortex.word2vecUserSimilarity(user1uris, user2uris)
    val s = Await.result(sfuture, 60 seconds)
    val res = s match {
      case Some(score) => s"similarity score for user ${user1} and user ${user2}: ${score}"
      case None => s"we seem do not have enough information for one of the user"
    }
    Ok
  }

  def index() = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.word2vec())
  }

}
