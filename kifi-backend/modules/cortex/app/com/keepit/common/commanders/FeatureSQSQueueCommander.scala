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
import com.keepit.common.logging.Logging


@Singleton
class FeatureSQSQueueCommander(
  basicAWSCreds: BasicAWSCredentials,
  featureCommander: FeatureRetrievalCommander
) extends Logging {
  val DEFAULT_PUSH_SIZE = 100
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

    log.info(s"start pulling features from seq = ${seq}, version = ${version}")
    val t = System.currentTimeMillis()
    val msgs = getLDAURIUpateMessgages(seq, version)
    log.info(s"get ${msgs.size} msgs in ${(System.currentTimeMillis - t)/1000f} seconds")

    if (msgs.isEmpty){
      if (version.version < GraphUpdateConfigs.updateUpToLDAVersion.version){
        log.info(s"old version ${version.version} feature has exhausted. fetching new version ${GraphUpdateConfigs.updateUpToLDAVersion.version}")
        val msgs2 = getLDAURIUpateMessgages(SequenceNumber[NormalizedURI](-1L), GraphUpdateConfigs.updateUpToLDAVersion)
        msgs2.foreach{queue.send(_)}
      }
    } else {
      msgs.foreach{queue.send(_)}
    }
  }
}
