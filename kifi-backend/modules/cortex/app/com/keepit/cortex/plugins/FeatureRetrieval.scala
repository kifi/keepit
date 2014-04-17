package com.keepit.cortex.plugins

import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.{FeatureRepresentation, ModelVersion, StatModel}
import com.keepit.cortex.store.{CommitInfoKey, CommitInfoStore, FeatureStoreSequenceNumber, VersionedStore}

abstract class FeatureRetrieval[K, T, M <: StatModel](
  featureStore: VersionedStore[K, M, FeatureRepresentation[T, M]],
  commitInfoStore: CommitInfoStore[T, M],
  dataPuller: DataPuller[T]
){
  protected def genFeatureKey(datum: T): K

  private def getFeatureStoreSeq(version: ModelVersion[M]): FeatureStoreSequenceNumber[T, M] = {
    val commitKey = CommitInfoKey[T, M](version)
    commitInfoStore.get(commitKey) match {
      case Some(info) => info.seq
      case None => FeatureStoreSequenceNumber[T, M](-1L)
    }
  }

  def getByKey(key: K, version: ModelVersion[M]): Option[FeatureRepresentation[T, M]] = {
    featureStore.get(key, version)
  }

  private def getFeatureForEntities(entities: Seq[T], version: ModelVersion[M]): Seq[(T, FeatureRepresentation[T, M])] = {
    entities.flatMap{ ent =>
      val key = genFeatureKey(ent)
      featureStore.get(key, version) match {
        case Some(rep) => Some(ent, rep)
        case None => None
      }
    }
  }

  def getSince(lowSeq: SequenceNumber[T], fetchSize: Int, version: ModelVersion[M]): Seq[(T, FeatureRepresentation[T, M])] = {
    val featSeq = getFeatureStoreSeq(version)
    if (lowSeq.value >= featSeq.value) Seq()
    else {
      val entities = dataPuller.getSince(lowSeq, fetchSize)
      getFeatureForEntities(entities, version)
    }
  }

  def getBetween(lowSeq: SequenceNumber[T], highSeq: SequenceNumber[T], version: ModelVersion[M]): Seq[(T, FeatureRepresentation[T, M])] = {
    val featSeq = getFeatureStoreSeq(version)
    val highSeqCap = SequenceNumber[T](highSeq.value min featSeq.value)
    val entities = dataPuller.getBetween(lowSeq, highSeqCap)
    getFeatureForEntities(entities, version)
  }
}
