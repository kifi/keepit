package com.keepit.cortex.plugins

import com.keepit.cortex.store.CommitInfoStore
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.store.VersionedStore
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.model.NormalizedURI
import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.common.db.Id

abstract class URIFeatureUpdater[M <: StatModel](
  representer: FeatureRepresenter[NormalizedURI, M],
  featureStore: VersionedStore[Id[NormalizedURI], M, FeatureRepresentation[NormalizedURI, M]],
  commitInfoStore: CommitInfoStore[NormalizedURI, M],
  dataPuller: DataPuller[NormalizedURI]
) extends FeatureUpdater[Id[NormalizedURI], NormalizedURI, M](representer, featureStore, commitInfoStore, dataPuller){

  protected def getSeqNumber(uri: NormalizedURI): SequenceNumber[NormalizedURI] = uri.seq
  protected def genFeatureKey(uri: NormalizedURI): Id[NormalizedURI] = uri.id.get
}
