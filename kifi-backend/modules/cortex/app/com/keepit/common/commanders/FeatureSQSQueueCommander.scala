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


@Singleton
class FeatureSQSQueueCommander(
  basicAWSCreds: BasicAWSCredentials,
  featureCommander: FeatureRetrievalCommander
){
  val DEFAULT_PUSH_SIZE = 500

  private def createSQSClient = {
    SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)
  }

  def sendLDAURIFeature(lowSeq: SequenceNumber[NormalizedURI], version: ModelVersion[DenseLDA], sqsId: String): Unit = {
    val feats = featureCommander.getLDAURIFeature(lowSeq, DEFAULT_PUSH_SIZE, version)

    val client = createSQSClient
    val queue = client.formatted[DenseLDAURIFeatureMessage](QueueName(sqsId))

    feats.foreach{ case (uri, feat) =>
      val msg = DenseLDAURIFeatureMessage(uri.id.get, uri.seq, "dense_lda", version.version, feat.vectorize)
      queue.send(msg)
    }
  }
}
