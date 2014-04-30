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

  def graphLDAURIFeatureUpdate = Action(parse.tolerantJson){ request =>
    val js = request.body
    val lowSeq =  CortexVersionedSequenceNumber.fromLong[NormalizedURI]((js \ "versionedLowSeq").as[Long])
    val queueId = (js \ "queue").as[String]
    featureSQSCommander.graphLDAURIFeatureUpdate(lowSeq, QueueName(queueId))
    Status(202)("0")
  }

}
