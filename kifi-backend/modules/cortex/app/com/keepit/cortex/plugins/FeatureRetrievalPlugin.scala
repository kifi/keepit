package com.keepit.cortex.plugins

import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.store.CommitInfoStore
import com.keepit.cortex.store.VersionedStore
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.store.FeatureStoreSequenceNumber
import com.keepit.cortex.store.CommitInfoKey

trait FeatureRetrievalPlugin

abstract class FeatureRetrieval[K, T, M <: StatModel](
  representer: FeatureRepresenter[T, M],
  featureStore: VersionedStore[K, M, FeatureRepresentation[T, M]],
  commitInfoStore: CommitInfoStore[T, M],
  dataPuller: DataPuller[T]
){
  protected def genFeatureKey(datum: T): K

  private def getFeatureStoreSeq(): FeatureStoreSequenceNumber[T, M] = {
    val commitKey = CommitInfoKey[T, M](representer.version)
    commitInfoStore.get(commitKey) match {
      case Some(info) => info.seq
      case None => FeatureStoreSequenceNumber[T, M](-1L)
    }
  }

  def getBetween(lowSeq: SequenceNumber[T], highSeq: SequenceNumber[T]): Seq[(T, FeatureRepresentation[T, M])] = {
    val featSeq = getFeatureStoreSeq()
    val entities = if (featSeq.value < highSeq.value) dataPuller.getBetween(lowSeq, SequenceNumber[T](featSeq.value)) else dataPuller.getBetween(lowSeq, highSeq)
    entities.flatMap{ ent =>
      val key = genFeatureKey(ent)
      featureStore.get(key, representer.version) match {
        case Some(rep) => Some(ent, rep)
        case None => None
      }
    }
  }
}

