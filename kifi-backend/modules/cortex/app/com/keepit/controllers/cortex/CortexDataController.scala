package com.keepit.controllers.cortex

import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.commanders.FeatureSQSQueueCommander
import com.keepit.cortex.core.ModelVersion
import com.keepit.common.db.SequenceNumber
import com.kifi.franz.QueueName
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex._

class CortexDataController @Inject()(
  featureSQSCommander: FeatureSQSQueueCommander
) extends CortexServiceController {

  def sendLDAURIFeature = Action(parse.tolerantJson) { request =>
    val js = request.body
    val lowSeq = SequenceNumber[NormalizedURI]( (js \ "lowSeq").as[Long])
    val version = (js \ "version").as[Int]
    val queueId = (js \ "queue").as[String]

    assume(version == ModelVersions.denseLDAVersion.version)

    featureSQSCommander.sendLDAURIFeature(lowSeq, ModelVersion[DenseLDA](version), QueueName(queueId))
    Status(202)("0")
  }

}
