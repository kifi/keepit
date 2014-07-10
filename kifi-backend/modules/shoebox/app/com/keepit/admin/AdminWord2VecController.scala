package com.keepit.controllers.admin

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Random

import com.google.inject.Inject
import com.keepit.common.controller.{ ActionAuthenticator, AdminController }
import com.keepit.common.db.Id
import com.keepit.cortex.CortexServiceClient
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.shoebox.ShoeboxServiceClient

import play.api.libs.json._

import views.html

class AdminWord2VecController @Inject() (
    cortex: CortexServiceClient,
    shoebox: ShoeboxServiceClient,
    actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator) {

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

    val res = s"keywords(in the sense of cosine similarity): ${resp("keywords")}" + "\n\n" + s"bow: ${resp("bow")}"
    Ok(res.replaceAll("\n", "\n<br>"))
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

    val t1 = System.currentTimeMillis

    val user1uris = sampleUserUris(Id[User](user1), 100)
    val user2uris = sampleUserUris(Id[User](user2), 100)
    val sfuture = cortex.word2vecUserSimilarity(user1uris, user2uris)
    val s = Await.result(sfuture, 60 seconds)

    val elapse = (System.currentTimeMillis() - t1) / 1000f

    val res = s match {
      case Some(score) => s"Time elapsed: ${elapse} seconds. similarity score for user ${user1} and user ${user2}: ${score}"
      case None => s"we seem do not have enough information for one of the user"
    }

    Ok(res)
  }

  def queryUriSimilarity() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val query = body.get("query").get
    val uri = Id[NormalizedURI](body.get("uri").get.toLong)

    val s = Await.result(cortex.word2vecQueryUriSimilarity(query, uri), 5 seconds)
    val res = s match {
      case Some(score) => s"similarity score for query ${query} and uri ${uri}: ${score}"
      case None => s"we seem do not have enough information. Likely we don't have a feature vector for that uri in store."
    }
    Ok(res)
  }

  def userUriSimilarity() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val user = body.get("user").get.toLong
    val uri = Id[NormalizedURI](body.get("uri").get.toLong)

    val t1 = System.currentTimeMillis
    val userUris = sampleUserUris(Id[User](user), 100)
    val sim = Await.result(cortex.word2vecUserUriSimilarity(userUris, uri), 60 seconds).filter { case (uri, score) => score > 0.7f }.toArray.sortBy(-1f * _._2)
    val elapse = (System.currentTimeMillis() - t1) / 1000f

    val res = Json.obj("elapse" -> elapse, "uris" -> sim.map { _._1.toLong }, "scores" -> sim.map { _._2 })
    Ok(res)
  }

  def index() = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.word2vec())
  }

  private def sampleUserUris(userId: Id[User], sampleSize: Int): Seq[Id[NormalizedURI]] = {
    val uris = Await.result(shoebox.getBookmarks(userId), 5 seconds).map { _.uriId }
    if (uris.size < sampleSize) uris
    else Random.shuffle(uris.toList).take(sampleSize)
  }
}
