package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
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

@Singleton
class TopicModelController  @Inject() (
  docTopicModel: DocumentTopicModel,
  wordTopicModel: WordTopicModel,
  topicPlugin: TopicUpdaterPlugin,
  userTopicRepo: UserTopicRepo,
  db: Database,
  actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator){

  val uriTopicHelper = new UriTopicHelper
  val userTopicHelper = new UserTopicByteArrayHelper

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
      topicId.toString + ": " + score
    }

    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val content = body.get("doc").get
    val topic = docTopicModel.getDocumentTopicDistribution(content)


    val topics = uriTopicHelper.assignTopics(topic) match {
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
      arr.map{x => x._1 + ": " + "%.3f".format(x._2)}.mkString("\n")
    }

    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val word = body.get("word").get
    val topic = wordTopicModel.wordTopic.get(word) match {
      case Some(arr) => buildString( getTopTopics(arr) )
      case None => ""
    }

   Redirect(com.keepit.controllers.admin.routes.TopicModelController.wordTopic(Some(word), Some(topic)))
  }

  def userTopic(userId: Option[String] = None, topic: Option[String] = None) = AdminHtmlAction { implicit request =>
    Ok(html.admin.userTopic(userId, topic))
  }

  def getUserTopic = AdminHtmlAction { implicit request =>
    def getTopTopics(arr: Array[Int], topK: Int = 5) = {
       arr.zipWithIndex.filter(_._1 > 0).sortWith((a, b) => a._1 > b._1).take(topK).map{x => (x._2, x._1)}
    }

    def buildString(arr: Array[(Int, Int)]) = {
      arr.map{x => x._1 + ": " + x._2}.mkString("\n")
    }

    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = Id[User](body.get("user").get.toLong)
    val topic = db.readOnly { implicit s =>
      userTopicRepo.getByUserId(userId) match {
        case Some(userTopic) => userTopicHelper.toIntArray(userTopic.topic)
        case None => Array.empty[Int]
      }
    }

    val rv = buildString( getTopTopics(topic) )
    Redirect(com.keepit.controllers.admin.routes.TopicModelController.userTopic(Some(userId.id.toString), Some(rv)))
  }

}
