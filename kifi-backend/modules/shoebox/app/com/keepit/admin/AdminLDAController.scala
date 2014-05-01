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

  val MAX_WIDTH = 15

  def index() = AdminHtmlAction.authenticated { implicit request =>
    val n = Await.result(cortex.ldaNumOfTopics, 5 seconds)
    Ok(html.admin.lda(n))
  }

  private def trimLongString(s: String) = {
    if (s.length <= MAX_WIDTH) s
    else s.take(MAX_WIDTH - 3) + "..."
  }

  private def getFormatted(words: Map[String, Float]): String = {
    val width = (words.keys.map{_.length}.foldLeft(0)(_ max _) + 1) min MAX_WIDTH
    words.toArray.sortBy(-1f * _._2).grouped(5).map{ gp =>
      gp.map{ case (w, s) => s"%${width}s".format(trimLongString(w)) + "  " +  "%.4f".format(s) }.mkString("  ")
    }.mkString("\n")
  }

  def showTopics() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val fromId = body.get("fromId").get.toInt
    val toId = body.get("toId").get.toInt
    val topN = body.get("topN").get.toInt
    val res = Await.result(cortex.ldaShowTopics(fromId, toId, topN), 5 seconds)

    val topics = res.toArray.sortBy(_._1.toInt).map{ case (id, map) =>
      getFormatted(map)
    }

    Ok(Json.toJson(topics))
  }

  private def showTopTopicDistributions(arr: Array[Float]): String = {
    arr.zipWithIndex.sortBy(-1f * _._1).take(5).map{ case (score, topicId) => topicId + ": " + "%.3f".format(score)}.mkString(", ")
  }

  def wordTopic() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val word = body.get("word").get
    val res = Await.result(cortex.ldaWordTopic(word), 5 seconds)

    val msg = res match {
      case Some(arr) => showTopTopicDistributions(arr)
      case None => "word not in dictionary"
    }

    Ok(msg)
  }

  def docTopic() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val doc = body.get("doc").get
    val res = Await.result(cortex.ldaDocTopic(doc), 5 seconds)

    val msg = res match {
      case Some(arr) => showTopTopicDistributions(arr)
      case None => "not enough information."
    }

    Ok(msg)
  }
}
