package com.keepit.controllers.admin

import com.google.inject.{Provider, Inject, Singleton}
import com.keepit.common.controller.AdminController
import com.keepit.common.controller.ActionAuthenticator
import views.html
import com.keepit.learning.topicmodel._
import com.keepit.model.UriTopicHelper
import com.keepit.model.UserTopicByteArrayHelper
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.model.UserTopicRepo
import com.keepit.common.db.slick.Database
import com.keepit.model.TopicNameRepoA
import scala.math.ceil
import com.keepit.model.TopicName
import play.api.libs.json.Json

@Singleton
class TopicModelController  @Inject() (
  docTopicModel: DocumentTopicModel,
  wordTopicModel: Provider[WordTopicModel],
  topicPlugin: TopicUpdaterPlugin,
  topicNameMapper: Provider[TopicNameMapper],
  db: Database,
  modelAccessor: SwitchableTopicModelAccessor,
  actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator){

  val uriTopicHelper = new UriTopicHelper
  val userTopicHelper = new UserTopicByteArrayHelper

  def currentAccessor = modelAccessor.getActiveAccessor

  // dangerous operation ! Test purpose only. This interface will be removed soon.
  def switchModel = AdminHtmlAction { implicit request =>
    val prevFlag = modelAccessor.getCurrentFlag
    modelAccessor.switchAccessor()
    val currFlag = modelAccessor.getCurrentFlag
    Ok(s"OK. topic model has been switched from ${prevFlag} to ${currFlag}. Starting to use a different model and talk to different database tables!")
  }

  def resetAllTopicTables() = AdminHtmlAction{ implicit request =>
    topicPlugin.reset()
    Ok(s"OK. Will reset topic tables")
  }

  def documentTopic(content: Option[String] = None, topicId: Option[String] = None) = AdminHtmlAction{ implicit request =>
    Ok(html.admin.documentTopic(content, topicId))
  }

  def inferTopic = AdminHtmlAction{ implicit request =>
    def makeString(topicId: Int, membership: Double) = {
      val score = "%.3f".format(membership)
      topicNameMapper.get.getMappedNameByNewId(topicId) + ": " + score  // use transferred indexes
    }

    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val content = body.get("doc").get
    val rawTopic = docTopicModel.getDocumentTopicDistribution(content)
    val topic = topicNameMapper.get.scoreMapper(rawTopic)          // indexes will be transferred

    val topics = uriTopicHelper.getBiggerTwo(topic) match {
      case (None, None) => ""
      case (Some(a), None) => makeString(a, topic(a))
      case (None, Some(b)) => makeString(b, topic(b))
      case (Some(a), Some(b)) => makeString(a, topic(a)) + ", " + makeString(b, topic(b))
    }
    Redirect(com.keepit.controllers.admin.routes.TopicModelController.documentTopic(Some(content), Some(topics)))
  }

  def wordTopic(word: Option[String] = None, topic: Option[String] = None) = AdminHtmlAction { implicit request =>
    Ok(html.admin.wordTopic(word, topic))
  }

  def getWordTopic = AdminHtmlAction { implicit request =>

    def getTopTopics(arr: Array[Double], topK: Int = 5) = {
       arr.zipWithIndex.filter(_._1 > 1.0/TopicModelGlobal.numTopics)
                       .sortWith((a, b) => a._1 > b._1).take(topK).map{x => (x._2, x._1)}
    }

    def buildString(arr: Array[(Int, Double)]) = {
      arr.map{x => topicNameMapper.get.getMappedNameByNewId(x._1) + ": " + "%.3f".format(x._2)}.mkString("\n")
    }

    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val word = body.get("word").get
    val topic = wordTopicModel.get.wordTopic.get(word) match {
      case Some(arr) => buildString( getTopTopics( topicNameMapper.get.scoreMapper(arr) ) )
      case None => ""
    }

   Redirect(com.keepit.controllers.admin.routes.TopicModelController.wordTopic(Some(word), Some(topic)))
  }

  def userTopic(userId: Option[String] = None, topic: Option[String] = None) = AdminHtmlAction { implicit request =>
    Ok(html.admin.userTopic(userId, topic))
  }

  def getUserTopic = AdminHtmlAction { implicit request =>

    def buildString(score: Array[Int], topK: Int = 5) = {
      val newScore = topicNameMapper.get.scoreMapper(score)
      val tops = newScore.zipWithIndex.filter(_._1 > 0).sortWith((a, b) => a._1 > b._1).take(topK).map{x => (x._2, x._1)}
      tops.map{x => topicNameMapper.get.getMappedNameByNewId(x._1) + ": " + x._2}.mkString("\n")      // NOTE: use new id after score transformation
    }

    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = Id[User](body.get("user").get.toLong)
    val topic = db.readOnly { implicit s =>
      currentAccessor.userTopicRepo.getByUserId(userId) match {
        case Some(userTopic) => userTopicHelper.toIntArray(userTopic.topic)
        case None => Array.empty[Int]
      }
    }

    val rv = buildString(topic)
    Redirect(com.keepit.controllers.admin.routes.TopicModelController.userTopic(Some(userId.id.toString), Some(rv)))
  }

  def updateTopicName(id: Id[TopicName]) = AdminHtmlAction{ implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val topicName = body.get("topicName").get
    db.readWrite{ implicit s =>
      currentAccessor.topicNameRepo.updateName(id, topicName)
    }
    Ok(Json.obj("topicName" -> topicName))

  }

  def topicsViewDefault = topicsView(0)

  def topicsView(page: Int = 0) = AdminHtmlAction{ request =>
    val PAGE_SIZE = 50
    val (topics, count) = db.readOnly{ implicit s =>
      val topics = currentAccessor.topicNameRepo.all.sortWith((a, b) => a.id.get.id < b.id.get.id)
      val count = topics.size
      (topics.drop(page * PAGE_SIZE).take(PAGE_SIZE), count)
    }
    val pageCount = ceil(count*1.0 / PAGE_SIZE).toInt

    Ok(html.admin.topicNames(topics, page, count, pageCount, PAGE_SIZE))
  }

  def addTopics = AdminHtmlAction { implicit request =>
    Ok(html.admin.addTopicNames())
  }

  def saveAddedTopics = AdminHtmlAction{ implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val content = body.get("topics").get
    val topicNames = content.split("\n").map{_.trim}.filter(_ != "")
    val topics = topicNames.map{ name => TopicName(topicName = name) }

    db.readWrite{ implicit s =>
      currentAccessor.topicNameRepo.deleteAll()
      topics.foreach{currentAccessor.topicNameRepo.save(_)}
    }

    Redirect(com.keepit.controllers.admin.routes.TopicModelController.topicsViewDefault)
  }

}
