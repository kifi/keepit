package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.cortex.dbmodel.{ URILDATopicStates }
import com.keepit.cortex.models.lda._
import com.keepit.cortex.core.ModelVersion
import com.keepit.common.db.SequenceNumber
import com.keepit.model.NormalizedURI
import com.keepit.cortex.ModelVersions

@Singleton
class FeatureRetrievalCommander @Inject() (
    ldaRetriever: LDADbFeatureRetriever,
    ldaCommander: LDACommander) {

  private val defaultSparsity = 2
  private val allowedVersion = ModelVersions.defaultLDAVersion

  def getSparseLDAFeaturesChanged(lowSeq: SequenceNumber[NormalizedURI], fetchSize: Int, version: ModelVersion[DenseLDA], sparsity: Int = defaultSparsity): Seq[UriSparseLDAFeatures] = {
    assume(version == allowedVersion, s"allowed lda version = ${allowedVersion}, queried for version = ${version}")
    val feats = ldaRetriever.getLDAFeaturesChanged(lowSeq, fetchSize, version)
    feats.map { feat =>
      val dim = ldaCommander.numOfTopics(version)
      if (feat.state == URILDATopicStates.ACTIVE) UriSparseLDAFeatures(feat.uriId, feat.uriSeq, generateSparseRepresentation(feat.sparseFeature.get, sparsity))
      else UriSparseLDAFeatures(feat.uriId, feat.uriSeq, SparseTopicRepresentation(dim, Map()))
    }
  }

  private def generateSparseRepresentation(feat: SparseTopicRepresentation, sparsity: Int): SparseTopicRepresentation = {
    val dim = feat.dimension
    val topicMap = feat.topics.toArray.sortBy(-1f * _._2).take(sparsity).toMap
    SparseTopicRepresentation(dim, topicMap)
  }
}
