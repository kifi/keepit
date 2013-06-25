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
  wordTopicMode: WordTopicModel,
  actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator){

  val uriTopicHelper = new UriTopicHelper

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

}