package com.keepit.common.commanders

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.google.inject.Singleton
import com.keepit.common.db.SequenceNumber
import com.keepit.common.queue.messages.DenseLDAURIFeatureMessage
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.model.NormalizedURI
import com.kifi.franz.{QueueName, SimpleSQSClient}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.graph.manager.LDAURITopicGraphUpdate


@Singleton
class FeatureSQSQueueCommander(
  basicAWSCreds: BasicAWSCredentials,
  featureCommander: FeatureRetrievalCommander
){
  val DEFAULT_PUSH_SIZE = 500
  private val sqsClient = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)

  def sendLDAURIFeature(lowSeq: SequenceNumber[NormalizedURI], version: ModelVersion[DenseLDA], queueName: QueueName): Unit = {
    val feats = featureCommander.getLDAURIFeature(lowSeq, DEFAULT_PUSH_SIZE, version)
    val queue = sqsClient.formatted[LDAURITopicGraphUpdate](queueName)

    feats.foreach{ case (uri, feat) =>
      val msg = LDAURITopicGraphUpdate(uri.id.get, uri.seq, "dense_lda", version.version, feat.vectorize)
      queue.send(msg)
    }
  }
}
