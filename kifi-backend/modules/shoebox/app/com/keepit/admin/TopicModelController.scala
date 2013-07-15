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
import scala.concurrent._
import ExecutionContext.Implicits.global

@Singleton
class TopicModelController  @Inject() (
  db: Database,
  topicPlugin: TopicUpdaterPlugin,
  modelAccessor: SwitchableTopicModelAccessor,
  wordTopicStore: WordTopicStore,
  wordTopicBlobStore: WordTopicBlobStore,
  wordStore: WordStore,
  topicWordsStore: TopicWordsStore,
  actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator){

  val uriTopicHelper = new UriTopicHelper
  val userTopicHelper = new UserTopicByteArrayHelper
  def currentAccessor = modelAccessor.getActiveAccessor
  def numTopics = currentAccessor.topicNameMapper.rawTopicNames.size

  def remodel() = AdminHtmlAction{ implicit request =>
    topicPlugin.remodel()
    Ok(s"OK. Will reconstruct topic model.")
  }

  def documentTopic(content: Option[String] = None, topicId: Option[String] = None) = AdminHtmlAction{ implicit request =>
    Ok(html.admin.documentTopic(content, topicId))
  }

  def inferTopic = AdminHtmlAction{ implicit request =>
    def makeString(topicId: Int, membership: Double) = {
      val score = "%.3f".format(membership)
      currentAccessor.topicNameMapper.getMappedNameByNewId(topicId) + ": " + score  // use transferred indexes
    }

    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val content = body.get("doc").get
    val rawTopic = currentAccessor.documentTopicModel.getDocumentTopicDistribution(content, numTopics)
    val topic = currentAccessor.topicNameMapper.scoreMapper(rawTopic)          // indexes will be transferred

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
       arr.zipWithIndex.filter(_._1 > 1.0/numTopics)
                       .sortWith((a, b) => a._1 > b._1).take(topK).map{x => (x._2, x._1)}
    }

    def buildString(arr: Array[(Int, Double)]) = {
      arr.map{x => currentAccessor.topicNameMapper.getMappedNameByNewId(x._1) + ": " + "%.3f".format(x._2)}.mkString("\n")
    }

    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val word = body.get("word").get
    val topic = currentAccessor.wordTopicModel.wordTopic.get(word) match {
      case Some(arr) => buildString( getTopTopics( currentAccessor.topicNameMapper.scoreMapper(arr) ) )
      case None => ""
    }

   Redirect(com.keepit.controllers.admin.routes.TopicModelController.wordTopic(Some(word), Some(topic)))
  }

  def userTopic(userId: Option[String] = None, topic: Option[String] = None) = AdminHtmlAction { implicit request =>
    Ok(html.admin.userTopic(userId, topic))
  }

  def getUserTopic = AdminHtmlAction { implicit request =>

    def buildString(score: Array[Int], topK: Int = 5) = {
      val newScore = currentAccessor.topicNameMapper.scoreMapper(score)
      val tops = newScore.zipWithIndex.filter(_._1 > 0).sortWith((a, b) => a._1 > b._1).take(topK).map{x => (x._2, x._1)}
      tops.map{x => currentAccessor.topicNameMapper.getMappedNameByNewId(x._1) + ": " + x._2}.mkString("\n")      // NOTE: use new id after score transformation
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

  def genModelFiles(flag: String) = AdminHtmlAction{ implicit request =>
    future {
      log.info(s"loading model files for model ${flag}")

      val id = flag match {
        case TopicModelAccessorFlag.A => "model_a"
        case TopicModelAccessorFlag.B => "model_b"
      }

      val content = wordTopicStore.get(id).get
      val loader = new LdaTopicModelLoader
      val model = loader.load(content)

      val words = model.wordTopic.map{_._1}.toArray
      val arrs = model.wordTopic.map{_._2}.flatten.toArray

      log.info(s"writing converted model files for model ${flag} to S3")
      log.info(s"num of words: ${words.size}, topic vector size: ${arrs.size}")

      wordStore += (id, words)            // overwrite/create id.words.json
      wordTopicBlobStore += (id, arrs)    // overwrite/create id.topicVector.bin
      log.info("model data has been written to S3")
    }

    Ok(s"word list and topic binary array for model ${flag} will be created in S3")
  }

  def viewTopicWords(flag: String) = AdminHtmlAction{ implicit request =>
    val id = flag match {
      case TopicModelAccessorFlag.A => "model_a"
      case TopicModelAccessorFlag.B => "model_b"
    }
    log.info("getting topic words from S3")
    val content = topicWordsStore.get(id).get
    Ok(content)
  }

  // index is not necessarily the same as Id in DB. index starts from 1.
  def viewTopicDetails(index: Int) = AdminHtmlAction{ implicit request =>
    val topic = db.readOnly { implicit s =>
      val topics = currentAccessor.topicNameRepo.all.sortWith((a, b) => a.id.get.id < b.id.get.id)
      topics(index - 1)
    }

    val name = topic.topicName

    val fileId = modelAccessor.getCurrentFlag match {
      case TopicModelAccessorFlag.A => "model_a"
      case TopicModelAccessorFlag.B => "model_b"
    }

    val content = topicWordsStore.get(fileId).get
    val idx = content.indexOfSlice("[1] \"top words in topic " + index)
    val idx2 = content.indexOfSlice("[1] \"top words in topic " + (index + 1).toString)
    val topWords = (idx, idx2) match {
      case (-1, _) => "invalid topicId"
      case (i, -1) => content.slice(i, content.size)
      case (i, j) => content.slice(i, j)
    }

    Ok(html.admin.topicDetails(index, topic, topWords))
  }

}
