package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.AdminController
import com.keepit.common.controller.ActionAuthenticator
import views.html
import com.keepit.learning.topicmodel._
import com.keepit.model.UriTopicHelper

@Singleton
class TopicModelController  @Inject() (
  docTopicModel: DocumentTopicModel,
  wordTopicModel: WordTopicModel,
  topicPlugin: TopicUpdaterPlugin,
  actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator){

  val uriTopicHelper = new UriTopicHelper

  def resetAllTopicTables() = AdminHtmlAction{ implicit request =>
    val (nUri, nUser) = topicPlugin.reset()
    Ok(s"topic tables have been reset. num uris deleted: ${nUri}, num users deleted: ${nUser}")
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

  def wordTopic(word: Option[String], topic: Option[String]) = AdminHtmlAction { implicit request =>
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

}