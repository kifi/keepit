package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ ActionAuthenticator, AdminController }
import com.keepit.cortex.CortexServiceClient
import com.keepit.shoebox.ShoeboxServiceClient
import views.html
import play.api.libs.json._
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.cortex.models.lda.LDATopicConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.model.KeepRepo
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future

class AdminLDAController @Inject() (
    cortex: CortexServiceClient,
    shoebox: ShoeboxServiceClient,
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    keepRepo: KeepRepo) extends AdminController(actionAuthenticator) {

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
    val width = (words.keys.map { _.length }.foldLeft(0)(_ max _) + 1) min MAX_WIDTH
    words.toArray.sortBy(-1f * _._2).grouped(4).map { gp =>
      gp.map { case (w, s) => s"%${width}s".format(trimLongString(w)) + "  " + "%.4f".format(s) }.mkString("  ")
    }.mkString("\n")
  }

  def showTopics() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val fromId = body.get("fromId").get.toInt
    val toId = body.get("toId").get.toInt
    val topN = body.get("topN").get.toInt
    val res = Await.result(cortex.ldaShowTopics(fromId, toId, topN), 5 seconds)

    val ids = res.map { _.topicId }
    val topics = res.map { x => getFormatted(x.topicWords) }
    val names = res.map { _.config.topicName }
    val states = res.map { _.config.isActive }
    val js = Json.obj("ids" -> ids, "topicWords" -> topics, "topicNames" -> names, "states" -> states)

    Ok(js)
  }

  private def showTopTopicDistributions(arr: Array[Float]): String = {
    arr.zipWithIndex.sortBy(-1f * _._1).take(5).map { case (score, topicId) => topicId + ": " + "%.3f".format(score) }.mkString(", ")
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

  def saveEdits() = AdminHtmlAction.authenticated(parse.tolerantJson) { implicit request =>
    val js = request.body

    println(js)

    val ids = (js \ "topic_ids").as[JsArray].value.map { _.as[String] }
    val names = (js \ "topic_names").as[JsArray].value.map { _.as[String] }
    val isActive = (js \ "topic_states").as[JsArray].value.map { _.as[Boolean] }

    println(ids)
    println(names)
    println(isActive)

    val config = (ids, names, isActive).zipped.map { case (id, name, active) => id -> LDATopicConfiguration(name, active) }.toMap
    cortex.saveEdits(config)
    Ok
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

  def userTopicDump(userId: Id[User], limit: Int) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val uris = db.readOnlyMaster { implicit s => keepRepo.getLatestKeepsURIByUser(userId, limit, includePrivate = false) }
    cortex.getLDAFeatures(uris).map { feats =>
      Ok(Json.toJson(feats))
    }
  }
}
