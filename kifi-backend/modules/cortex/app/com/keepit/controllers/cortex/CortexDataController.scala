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
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.actor.ActorInstance

class CortexGraphController @Inject()(
  actor: ActorInstance[CortexGraphUpdateActor]
) extends CortexServiceController {

  def graphLDAURIFeatureUpdate = Action(parse.tolerantJson){ request =>
    val js = request.body
    val lowSeq =  CortexVersionedSequenceNumber.fromLong[NormalizedURI]((js \ "versionedLowSeq").as[Long])
    val queueId = (js \ "queue").as[String]
    actor.ref ! LDAURIFeatureUpdateMessage(lowSeq, QueueName(queueId))
    Status(202)("0")
  }
}

private sealed trait CortexGraphUpateMessage
private case class LDAURIFeatureUpdateMessage(lowSeq: CortexVersionedSequenceNumber[NormalizedURI], queue: QueueName) extends CortexGraphUpateMessage

private class CortexGraphUpdateActor @Inject()(
  featureSQSCommander: FeatureSQSQueueCommander,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) {

  private class UnknownCortexGraphUpateMessageException(msg: String) extends Exception

  def receive = {
    case LDAURIFeatureUpdateMessage(lowSeq, queue) => featureSQSCommander.graphLDAURIFeatureUpdate(lowSeq, queue)
    case msg => throw new UnknownCortexGraphUpateMessageException(s"unknown cortex graph update message: ${msg.toString}")
  }
}
