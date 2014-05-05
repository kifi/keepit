package com.keepit.common.commanders

import com.google.inject.{Inject, Singleton}
import com.keepit.cortex.models.lda.LDAURIFeatureRetriever
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.db.SequenceNumber
import com.keepit.model.NormalizedURI
import com.keepit.cortex.core.FeatureRepresentation

@Singleton
class FeatureRetrievalCommander @Inject()(
  ldaURIFeat: LDAURIFeatureRetriever
) {
  def getLDAURIFeature(lowSeq: SequenceNumber[NormalizedURI], fetchSize: Int, version: ModelVersion[DenseLDA]): Seq[(NormalizedURI, FeatureRepresentation[NormalizedURI, DenseLDA])] = {
    ldaURIFeat.getSince(lowSeq, fetchSize, version)
  }
}
