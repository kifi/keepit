package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.cortex.models.lda._
import com.keepit.cortex.core.ModelVersion
import com.keepit.common.db.SequenceNumber
import com.keepit.model.NormalizedURI
import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.cortex._
import com.keepit.cortex.models.lda.DenseLDA

@Singleton
class FeatureRetrievalCommander @Inject() (
    ldaURIFeat: LDAURIFeatureRetriever) {
  def getLDAFeaturesChanged(lowSeq: SequenceNumber[NormalizedURI], fetchSize: Int, version: ModelVersion[DenseLDA]): Seq[(NormalizedURI, FeatureRepresentation[NormalizedURI, DenseLDA])] = {
    ldaURIFeat.trickyGetSince(lowSeq, fetchSize, version)
  }

  def getSparseLDAFeaturesChanged(lowSeq: SequenceNumber[NormalizedURI], fetchSize: Int, version: ModelVersion[DenseLDA], sparsity: Int = PublishedModels.defaultSparsity): Seq[UriSparseLDAFeatures] = {
    val features = getLDAFeaturesChanged(lowSeq, fetchSize, version)
    features.map {
      case (uri, feat) =>
        UriSparseLDAFeatures(uri.id.get, uri.seq, generateSparseRepresentation(feat.vectorize, sparsity))
    }
  }

  private def generateSparseRepresentation(topicVector: Array[Float], sparsity: Int): SparseTopicRepresentation = {
    val dim = topicVector.length
    val topicMap = topicVector.zipWithIndex.sortBy(-1f * _._1).take(sparsity).map { case (score, idx) => (LDATopic(idx), score) }.toMap
    SparseTopicRepresentation(dim, topicMap)
  }
}
