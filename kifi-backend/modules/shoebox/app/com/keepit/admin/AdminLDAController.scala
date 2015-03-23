package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.cortex.CortexServiceClient
import com.keepit.cortex.core.ModelVersion
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json._
import scala.concurrent.{ Await, Future }
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA, LDATopicDetail, LDATopicConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits._
import views.html
import scala.concurrent.duration._

class AdminLDAController @Inject() (
    cortex: CortexServiceClient,
    shoebox: ShoeboxServiceClient,
    val userActionsHelper: UserActionsHelper,
    db: Database,
    userRepo: UserRepo,
    uriRepo: NormalizedURIRepo,
    personaRepo: PersonaRepo,
    keepRepo: KeepRepo) extends AdminUserActions {

  val MAX_WIDTH = 15

  implicit def int2VersionOpt(n: Int) = Some(ModelVersion[DenseLDA](n))
  implicit def int2Version(n: Int) = ModelVersion[DenseLDA](n)

  lazy val defaultVersion = Await.result(cortex.defaultLDAVersion(), 1 second)

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
      val pmiScores = res.map { _.pmiScore.getOrElse(Float.NaN) }.map { x => "%.3f".format(x) }
      val names = res.map { _.config.topicName }
      val states = res.map { _.config.isActive }
      val nameables = res.map { _.config.isNameable }
      val js = Json.obj("ids" -> ids, "topicWords" -> topics, "pmiScores" -> pmiScores, "topicNames" -> names, "states" -> states, "nameables" -> nameables)
      Ok(js)
    }
  }

  private def showTopTopicDistributions(arr: Array[Float], topK: Int = 5)(version: ModelVersion[DenseLDA]): Future[String] = {
    cortex.ldaConfigurations(Some(version)).map { ldaConf =>
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
      case Some(arr) => showTopTopicDistributions(arr)(version)
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
      case Some(arr) => showTopTopicDistributions(arr)(version)
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
          case Some(arr) => showTopTopicDistributions(arr, topK = 10)(version)
          case None => Future.successful("not enough information")
        }

        val recentMsgFut = recent match {
          case Some(arr) => showTopTopicDistributions(arr, topK = 10)(version)
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
    cortex.sampleURIsForTopic(topicId)(int2VersionOpt(version)).map {
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
    val version = versionOpt.flatMap { int2VersionOpt(_) } getOrElse defaultVersion
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
        case Some(arr) => showTopTopicDistributions(arr, topK = 10)(version)
        case None => Future.successful("not enough information")
      }
    }

    msgFut.map { msg =>
      Ok(msg)
    }

  }

  def userLibraryScore() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = Id[User](body.get("userId").get.trim.toLong)
    val libId = Id[Library](body.get("libId").get.trim.toLong)
    val version = body.get("version").get.trim.toInt

    cortex.userLibraryScore(userId, libId)(version).map { scoreOpt =>
      scoreOpt match {
        case Some(s) => Ok(s.toString)
        case None => Ok("na")
      }
    }
  }

  def similarURIs(uriId: Id[NormalizedURI]) = AdminUserPage.async { implicit request =>
    val ver = defaultVersion
    cortex.similarURIs(uriId)(Some(ver)).map { uriIds =>
      val uris = db.readOnlyReplica { implicit s =>
        uriIds.map { id => uriRepo.get(id) }
      }
      Ok(html.admin.ldaSimilarURIs(ver.version, uris))
    }
  }

  def persona() = personaVersioned(defaultVersion)

  def personaVersioned(version: ModelVersion[DenseLDA]) = AdminUserPage.async { implicit request =>
    cortex.ldaNumOfTopics(Some(version)).map { n =>
      Ok(html.admin.ldaPersona(n, version.version))
    }
  }

  def generateLDAPersonaFeature() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val pid = body.get("personaId").get.trim.toInt
    val tids = body.get("topicIds").get.split(", ").map { x => LDATopic(x.trim.toInt) }
    val version = body.get("version").get.trim.toInt
    cortex.generatePersonaFeature(tids)(version).map { res =>
      // save immediately
      cortex.savePersonaFeature(Id[Persona](pid), res._1)(version)
      Ok(Json.obj("feature" -> res._1, "sampleSize" -> res._2))
    }
  }

  def evaluatePersona() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val pid = body.get("personaId").get.trim.toInt
    val pname = db.readOnlyReplica { implicit s => personaRepo.get(Id[Persona](pid)).name.value }
    val version = body.get("version").get.trim.toInt
    cortex.evaluatePersona(Id[Persona](pid))(version).map { uriScores =>
      val uids = uriScores.toArray.sortBy(-_._2).map { _._1 }
      val scores = uids.map { uriScores(_) }.map { x => "%.3f".format(x) }
      val titles = db.readOnlyReplica { implicit s => uids.map { uriRepo.get(_) } }.map { _.title.getOrElse("n/a") }
      val shorterTitles = titles.map { t =>
        val suffix = if (t.size > 80) "..." else ""
        t.take(80) + suffix
      }
      Ok(Json.obj("persona" -> pname, "uids" -> uids, "titles" -> shorterTitles, "scores" -> scores))
    }
  }

  def personaFeatureTraining() = AdminUserPage(parse.tolerantJson) { implicit request =>
    val js = request.body

    val pid = (js \ "personaId").as[String].trim.toInt
    val version = (js \ "version").as[JsNumber].value.toInt
    val uids = (js \ "uids").as[Seq[String]].map { _.trim.toInt }
    val labels = (js \ "feedbacks").as[Seq[Int]]
    val rate = ((js \ "rate").as[String].trim.toFloat min 0.1f) max 0.001f

    require(uids.length == labels.length)

    // ignore neutral feedbacks
    val (uids2, labels2) = (uids zip labels).filter { _._2 != 0 }.unzip
    cortex.trainPersona(Id[Persona](pid), uids2.map { Id[NormalizedURI](_) }, labels2, rate)(version)

    Ok
  }
}
