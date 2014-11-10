package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.cortex.CortexServiceClient
import com.keepit.cortex.core.ModelVersion
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json._
import scala.concurrent.Future
import com.keepit.cortex.models.lda.{ DenseLDA, LDATopicDetail, LDATopicConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits._
import views.html

class AdminLDAController @Inject() (
    cortex: CortexServiceClient,
    shoebox: ShoeboxServiceClient,
    val userActionsHelper: UserActionsHelper,
    db: Database,
    userRepo: UserRepo,
    uriRepo: NormalizedURIRepo,
    keepRepo: KeepRepo) extends AdminUserActions {

  val MAX_WIDTH = 15

  implicit def int2Version(n: Int) = Some(ModelVersion[DenseLDA](n))

  val defaultVersion = ModelVersion[DenseLDA](2) // TODO soon: get this from cortex client

  def index() = versionPage(defaultVersion)

  def versionPage(version: ModelVersion[DenseLDA]) = AdminUserPage.async { implicit request =>
    cortex.ldaNumOfTopics(Some(version)).map { n =>
      Ok(html.admin.lda(n, version.version))
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

  def showTopics() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val fromId = body.get("fromId").get.trim.toInt
    val toId = body.get("toId").get.trim.toInt
    val topN = body.get("topN").get.trim.toInt
    val version = body.get("version").get.trim.toInt
    cortex.ldaShowTopics(fromId, toId, topN)(version).map { res =>
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

  def wordTopic() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val word = body.get("word").get
    val version = body.get("version").get.trim.toInt
    val futureMsg = cortex.ldaWordTopic(word)(version).flatMap {
      case Some(arr) => showTopTopicDistributions(arr)
      case None => Future.successful("word not in dictionary")
    }
    futureMsg.map(msg => Ok(JsString(msg)))
  }

  def saveEdits() = AdminUserPage(parse.tolerantJson) { implicit request =>
    val js = request.body

    val ids = (js \ "topic_ids").as[JsArray].value.map { _.as[String] }
    val names = (js \ "topic_names").as[JsArray].value.map { _.as[String] }
    val isActive = (js \ "topic_states").as[JsArray].value.map { _.as[Boolean] }
    val isNameable = (js \ "topic_nameable").as[JsArray].value.map { _.as[Boolean] }
    val version = (js \ "version").as[JsNumber].value.toInt

    val config = (0 until ids.size).map { i =>
      val (id, name, active, nameable) = (ids(i), names(i), isActive(i), isNameable(i))
      id.trim() -> LDATopicConfiguration(name, active, nameable)
    }.toMap

    cortex.saveEdits(config)(version)
    Ok
  }

  def docTopic() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val doc = body.get("doc").get
    val version = body.get("version").get.trim.toInt
    val futureMsg = cortex.ldaDocTopic(doc)(version).flatMap {
      case Some(arr) => showTopTopicDistributions(arr)
      case None => Future.successful("not enough information.")
    }
    futureMsg.map(msg => Ok(JsString(msg)))
  }

  def userUriInterest() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = body.get("userId").get.toLong
    val uriId = body.get("uriId").get.toLong
    val version = body.get("version").get.trim.toInt
    val score = cortex.userUriInterest(Id[User](userId), Id[NormalizedURI](uriId))(version)
    score.map { score =>
      val (globalScore, recencyScore, libScore) = (score.global, score.recency, score.libraryInduced)
      val globalMsg = "globalScore: " + globalScore.map { _.toString }.getOrElse("n/a")
      val recencyMsg = "recencyScore: " + recencyScore.map { _.toString }.getOrElse("n/a")
      val libMsg = "libScore: " + libScore.map { _.toString }.getOrElse("n/a")
      val msg = List(globalMsg, recencyMsg, libMsg).mkString("\n")
      Ok(msg.replaceAll("\n", "\n<br>"))
    }
  }

  def userTopicMean() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = body.get("userId").get.toLong
    val version = body.get("version").get.trim.toInt

    cortex.userTopicMean(Id[User](userId))(version).flatMap {
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

  def topicDetail(topicId: Int, version: Int) = AdminUserPage.async { implicit request =>
    cortex.sampleURIsForTopic(topicId).map {
      case (uriIds, scores) =>
        val uris = db.readOnlyReplica { implicit s =>
          uriIds.map { id => uriRepo.get(id) }
        }
        Ok(html.admin.ldaDetail(version, LDATopicDetail(topicId, uris, scores)))
    }
  }

  def peopleLikeYou(topK: Int) = AdminUserPage.async { implicit request =>
    val user = request.userId
    cortex.getSimilarUsers(user, topK).map {
      case (userIds, scores) =>
        val users = db.readOnlyMaster { implicit s => userIds.map { id => userRepo.get(id) } }
        Ok(html.admin.peopleLikeYou(users, scores))
    }

  }

  def unamedTopics(limit: Int, versionOpt: Option[Int]) = AdminUserPage.async { implicit request =>
    val version = versionOpt.flatMap { int2Version(_) } getOrElse defaultVersion
    cortex.unamedTopics(limit)(Some(version)).map {
      case (topicInfo, topicWords) =>
        val words = topicWords.map { case words => getFormatted(words) }
        Ok(html.admin.unamedTopics(topicInfo, words, version.version))
    }
  }

  def libraryTopic() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val libId = Id[Library](body.get("libId").get.toLong)
    val version = body.get("version").get.trim.toInt

    val msgFut = cortex.libraryTopic(libId)(version).flatMap { feat =>
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
