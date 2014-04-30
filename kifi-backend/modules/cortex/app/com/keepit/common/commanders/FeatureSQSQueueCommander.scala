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
import com.keepit.cortex.CortexVersionedSequenceNumber
import com.keepit.cortex._


@Singleton
class FeatureSQSQueueCommander(
  basicAWSCreds: BasicAWSCredentials,
  featureCommander: FeatureRetrievalCommander
){
  val DEFAULT_PUSH_SIZE = 500
  private val sqsClient = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)

  private def getLDAURIUpateMessgages(lowSeq: SequenceNumber[NormalizedURI], version: ModelVersion[DenseLDA]): Seq[LDAURITopicGraphUpdate] = {
    val feats = featureCommander.getLDAURIFeature(lowSeq, DEFAULT_PUSH_SIZE, version)
    feats.map{ case (uri, feat) =>
      val vseq = CortexVersionedSequenceNumber[NormalizedURI](version.version, uri.seq.value)
      LDAURITopicGraphUpdate(uri.id.get, vseq, "dense_lda", feat.vectorize)
    }
  }

  def graphLDAURIFeatureUpdate(lowSeq: CortexVersionedSequenceNumber[NormalizedURI], queueName: QueueName): Unit = {
    val queue = sqsClient.formatted[LDAURITopicGraphUpdate](queueName)
    val (seq, version) = (SequenceNumber[NormalizedURI](lowSeq.seq), ModelVersion[DenseLDA](lowSeq.version))
    val msgs = getLDAURIUpateMessgages(seq, version)

    if (msgs.isEmpty && version.version < GraphUpdateConfigs.updateUpToLDAVersion.version){
      // old version consumed up. If cortex is ready to push next version, do it.
      val msgs2 = getLDAURIUpateMessgages(SequenceNumber[NormalizedURI](-1L), GraphUpdateConfigs.updateUpToLDAVersion)
      msgs2.foreach{queue.send(_)}
    } else {
      msgs.foreach{queue.send(_)}
    }
  }
}
