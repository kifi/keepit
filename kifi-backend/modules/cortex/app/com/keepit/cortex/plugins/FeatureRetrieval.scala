package com.keepit.cortex.plugins

import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.{FeatureRepresentation, ModelVersion, StatModel}
import com.keepit.cortex.store.{CommitInfoKey, CommitInfoStore, FeatureStoreSequenceNumber, VersionedStore}
import com.keepit.common.logging.Logging

abstract class FeatureRetrieval[K, T, M <: StatModel](
  featureStore: VersionedStore[K, M, FeatureRepresentation[T, M]],
  commitInfoStore: CommitInfoStore[T, M],
  dataPuller: DataPuller[T]
) extends Logging{
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
    val keys = entities.map{genFeatureKey(_)}

    val start = System.currentTimeMillis
    val values = featureStore.batchGet(keys, version)
    val ret = (entities zip values).filter(_._2.isDefined).map{case (ent, valOpt) => (ent, valOpt.get)}

    log.info(s"batch retrieved ${ret.size}/${keys.size}  objects in ${(System.currentTimeMillis - start)/1000f} seconds")
    ret
  }

  def getSince(lowSeq: SequenceNumber[T], fetchSize: Int, version: ModelVersion[M]): Seq[(T, FeatureRepresentation[T, M])] = {
    val featSeq = getFeatureStoreSeq(version)
    if (lowSeq.value >= featSeq.value) Seq()
    else {
      val entities = dataPuller.getSince(lowSeq, fetchSize)
      getFeatureForEntities(entities, version)
    }
  }
}
