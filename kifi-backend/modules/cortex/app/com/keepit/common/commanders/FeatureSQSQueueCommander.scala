package com.keepit.common.commanders

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.google.inject.Singleton
import com.keepit.common.db.SequenceNumber
import com.keepit.common.queue.messages.DenseLDAURIFeatureMessage
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex.models.lda.SparseTopicRepresentation
import com.keepit.model.NormalizedURI
import com.kifi.franz.{QueueName, SimpleSQSClient}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.graph.manager.{GraphUpdate, LDAURITopicGraphUpdate}
import com.keepit.cortex.CortexVersionedSequenceNumber
import com.keepit.cortex._
import com.keepit.common.logging.Logging
import com.google.inject.Inject


@Singleton
class FeatureSQSQueueCommander @Inject()(
  basicAWSCreds: BasicAWSCredentials,
  featureCommander: FeatureRetrievalCommander
) extends Logging {
  val DEFAULT_PUSH_SIZE = 100
  private val sqsClient = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)

  private def getLDAURIUpateMessgages(lowSeq: SequenceNumber[NormalizedURI], version: ModelVersion[DenseLDA]): Seq[LDAURITopicGraphUpdate] = {
    val feats = featureCommander.getLDAURIFeature(lowSeq, DEFAULT_PUSH_SIZE, version)
    feats.map{ case (uri, feat) =>
      val vseq = CortexVersionedSequenceNumber[NormalizedURI](version.version, uri.seq.value)
      val sparseTopics = generateSparseRepresentation(feat.vectorize)
      LDAURITopicGraphUpdate(uri.id.get, vseq, "dense_lda", sparseTopics)
    }
  }

  private def generateSparseRepresentation(topicVector: Array[Float]): SparseTopicRepresentation = {
    val dim = topicVector.length
    val topicMap = topicVector.zipWithIndex.sortBy(-1f * _._1).take(5).map{ case (score, idx) => (idx, score)}.toMap
    SparseTopicRepresentation(dim, topicMap)
  }

  def graphLDAURIFeatureUpdate(lowSeq: CortexVersionedSequenceNumber[NormalizedURI], queueName: QueueName): Unit = {
    val queue = sqsClient.formatted[GraphUpdate](queueName)
    val (seq, version) = (SequenceNumber[NormalizedURI](lowSeq.unversionedSeq), ModelVersion[DenseLDA](lowSeq.version))

    log.info(s"start pulling features from seq = ${seq}, version = ${version}")

    val t = System.currentTimeMillis()
    val seq2 = if (version.version < GraphUpdateConfigs.LDAVersionForGraphUpdate.version) SequenceNumber[NormalizedURI](-1L) else seq
    val msgs = getLDAURIUpateMessgages(seq2, GraphUpdateConfigs.LDAVersionForGraphUpdate)

    log.info(s"get ${msgs.size} msgs in ${(System.currentTimeMillis - t)/1000f} seconds")

    msgs.foreach{queue.send(_)}
  }
}
