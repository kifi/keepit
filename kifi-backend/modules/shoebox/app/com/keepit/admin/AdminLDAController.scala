package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ ActionAuthenticator, AdminController }
import com.keepit.cortex.CortexServiceClient
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json._
import scala.concurrent.Future
import com.keepit.cortex.models.lda.{ LDATopicDetail, LDATopicConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits._
import views.html

class AdminLDAController @Inject() (
    cortex: CortexServiceClient,
    shoebox: ShoeboxServiceClient,
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    userRepo: UserRepo,
    uriRepo: NormalizedURIRepo,
    keepRepo: KeepRepo) extends AdminController(actionAuthenticator) {

  val MAX_WIDTH = 15

  def index() = AdminHtmlAction.authenticatedAsync { implicit request =>
    cortex.ldaNumOfTopics.map { n =>
      Ok(html.admin.lda(n))
    }
  }

  private def trimLongString(s: String) = {
    if (s.length <= MAX_WIDTH) s
    else s.take(MAX_WIDTH - 3) + "..."
  }

  private def getFormatted(words: Map[String, Float]): String = {
    val width = (words.keys.map { _.length }.foldLeft(0)(_ max _) + 1) min MAX_WIDTH
    words.toArray.sortBy(-1f * _._2).grouped(4).map { gp =>
      gp.map { case (w, _) => s"%${width}s".format(trimLongString(w)) }.mkString("  ")
    }.mkString("\n")
  }

  def showTopics() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val fromId = body.get("fromId").get.toInt
    val toId = body.get("toId").get.toInt
    val topN = body.get("topN").get.toInt
    cortex.ldaShowTopics(fromId, toId, topN).map { res =>
      val ids = res.map { _.topicId }
      val topics = res.map { x => getFormatted(x.topicWords) }
      val names = res.map { _.config.topicName }
      val states = res.map { _.config.isActive }
      val nameables = res.map { _.config.isNameable }
      val js = Json.obj("ids" -> ids, "topicWords" -> topics, "topicNames" -> names, "states" -> states, "nameables" -> nameables)
      Ok(js)
    }
  }

  private def showTopTopicDistributions(arr: Array[Float], topK: Int = 5): Future[String] = {
    cortex.ldaConfigurations.map { ldaConf =>
      arr.zipWithIndex.sortBy(-1f * _._1).take(topK).map {
        case (score, topicId) =>
          val tname = ldaConf.configs(topicId.toString).topicName
          s"$topicId ($tname) : " + "%.3f".format(score)
      }.mkString(", ")
    }
  }

  def wordTopic() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val word = body.get("word").get
    val futureMsg = cortex.ldaWordTopic(word).flatMap {
      case Some(arr) => showTopTopicDistributions(arr)
      case None => Future.successful("word not in dictionary")
    }
    futureMsg.map(msg => Ok(JsString(msg)))
  }

  def saveEdits() = AdminHtmlAction.authenticated(parse.tolerantJson) { implicit request =>
    val js = request.body

    val ids = (js \ "topic_ids").as[JsArray].value.map { _.as[String] }
    val names = (js \ "topic_names").as[JsArray].value.map { _.as[String] }
    val isActive = (js \ "topic_states").as[JsArray].value.map { _.as[Boolean] }
    val isNameable = (js \ "topic_nameable").as[JsArray].value.map { _.as[Boolean] }

    val config = (0 until ids.size).map { i =>
      val (id, name, active, nameable) = (ids(i), names(i), isActive(i), isNameable(i))
      id.trim() -> LDATopicConfiguration(name, active, nameable)
    }.toMap

    cortex.saveEdits(config)
    Ok
  }

  def docTopic() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val doc = body.get("doc").get
    val futureMsg = cortex.ldaDocTopic(doc).flatMap {
      case Some(arr) => showTopTopicDistributions(arr)
      case None => Future.successful("not enough information.")
    }
    futureMsg.map(msg => Ok(JsString(msg)))
  }

  def userUriInterest() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = body.get("userId").get.toLong
    val uriId = body.get("uriId").get.toLong
    val score = cortex.userUriInterest(Id[User](userId), Id[NormalizedURI](uriId))
    score.map { score =>
      val (globalScore, recencyScore, libScore) = (score.global, score.recency, score.libraryInduced)
      val globalMsg = "globalScore: " + globalScore.map { _.toString }.getOrElse("n/a")
      val recencyMsg = "recencyScore: " + recencyScore.map { _.toString }.getOrElse("n/a")
      val libMsg = "libScore: " + libScore.map { _.toString }.getOrElse("n/a")
      val msg = List(globalMsg, recencyMsg, libMsg).mkString("\n")
      Ok(msg.replaceAll("\n", "\n<br>"))
    }
  }

  def userTopicMean() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = body.get("userId").get.toLong

    cortex.userTopicMean(Id[User](userId)).flatMap {
      case (global, recent) =>
        val globalMsgFut = global match {
          case Some(arr) => showTopTopicDistributions(arr, topK = 10)
          case None => Future.successful("not enough information")
        }

        val recentMsgFut = recent match {
          case Some(arr) => showTopTopicDistributions(arr, topK = 10)
          case None => Future.successful("not enough information")
        }

        for {
          globalMsg <- globalMsgFut
          recentMsg <- recentMsgFut
        } yield {
          val msg = "overall: " + globalMsg + "\n" + "recent: " + recentMsg
          Ok(msg.replaceAll("\n", "\n<br>"))
        }
    }

  }

  def topicDetail(topicId: Int) = AdminHtmlAction.authenticatedAsync { implicit request =>
    cortex.sampleURIsForTopic(topicId).map {
      case (uriIds, scores) =>
        val uris = db.readOnlyReplica { implicit s =>
          uriIds.map { id => uriRepo.get(id) }
        }
        Ok(html.admin.ldaDetail(LDATopicDetail(topicId, uris, scores)))
    }
  }

  def peopleLikeYou(topK: Int) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val user = request.userId
    cortex.getSimilarUsers(user, topK).map {
      case (userIds, scores) =>
        val users = db.readOnlyReplica { implicit s => userIds.map { id => userRepo.get(id) } }
        Ok(html.admin.peopleLikeYou(users, scores))
    }

  }

  def unamedTopics(limit: Int) = AdminHtmlAction.authenticatedAsync { implicit request =>
    cortex.unamedTopics(limit).map {
      case (topicInfo, topicWords) =>
        val words = topicWords.map { case words => getFormatted(words) }
        Ok(html.admin.unamedTopics(topicInfo, words))
    }
  }

  def libraryTopic() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val libId = Id[Library](body.get("libId").get.toLong)

    val msgFut = cortex.libraryTopic(libId).flatMap { feat =>
      feat match {
        case Some(arr) => showTopTopicDistributions(arr, topK = 10)
        case None => Future.successful("not enough information")
      }
    }

    msgFut.map { msg =>
      Ok(msg)
    }

  }
}
