package com.keepit.cortex.plugins

import com.keepit.common.db.{ Id, SequenceNumber, ModelWithSeqNumber }
import com.keepit.cortex.core.{ FeatureRepresentation, ModelVersion, StatModel }
import com.keepit.cortex.store.{ CommitInfoKey, CommitInfoStore, FeatureStoreSequenceNumber, VersionedStore }
import com.keepit.common.logging.Logging
import scala.collection.mutable.ArrayBuffer

abstract class FeatureRetrieval[K, T <: ModelWithSeqNumber[T], M <: StatModel, FT <: FeatureRepresentation[T, M]](
    featureStore: VersionedStore[K, M, FT],
    commitInfoStore: CommitInfoStore[T, M],
    dataPuller: DataPuller[T]) extends Logging {
  protected def genFeatureKey(datum: T): K
  private def getFeatureStoreSeq(version: ModelVersion[M]): FeatureStoreSequenceNumber[T, M] = {
    val commitKey = CommitInfoKey[T, M](version)
    commitInfoStore.syncGet(commitKey) match {
      case Some(info) => info.seq
      case None => FeatureStoreSequenceNumber[T, M](-1L)
    }
  }

  def getByKey(key: K, version: ModelVersion[M]): Option[FT] = {
    featureStore.syncGet(key, version)
  }

  def getByKeys(keys: Seq[K], version: ModelVersion[M]): Seq[Option[FT]] = {
    featureStore.syncBatchGet(keys, version)
  }

  private def getFeatureForEntities(entities: Seq[T], version: ModelVersion[M]): Seq[(T, FT)] = {
    val keys = entities.map { genFeatureKey(_) }
    val start = System.currentTimeMillis
    val values = featureStore.syncBatchGet(keys, version)
    val ret = (entities zip values).filter(_._2.isDefined).map { case (ent, valOpt) => (ent, valOpt.get) }

    log.info(s"batch retrieved ${ret.size}/${keys.size}  objects in ${(System.currentTimeMillis - start) / 1000f} seconds")
    ret
  }

  def getSince(lowSeq: SequenceNumber[T], fetchSize: Int, version: ModelVersion[M]): Seq[(T, FT)] = {
    val featSeq = getFeatureStoreSeq(version)
    if (lowSeq.value >= featSeq.value) Seq()
    else {
      val entities = dataPuller.getSince(lowSeq, fetchSize)
      getFeatureForEntities(entities, version)
    }
  }

  // this makes sure return size == fetchSize whenever there are enough data
  def trickyGetSince(lowSeq: SequenceNumber[T], fetchSize: Int, version: ModelVersion[M]): Seq[(T, FT)] = {
    val buf = ArrayBuffer.empty[(T, FT)]
    var nextBatchSize = fetchSize * 2
    var startSeq = lowSeq
    var exhausted = false
    while (buf.size < fetchSize && !exhausted) {
      val feats = getSince(startSeq, nextBatchSize, version)
      if (feats.isEmpty) {
        exhausted = true
      } else {
        buf.appendAll(feats)
        startSeq = feats.map { case (ent, feat) => ent.seq }.max
        nextBatchSize = ((fetchSize - buf.size) * 2 min fetchSize) max 10 // overfetch
      }
    }
    buf.take(fetchSize)
  }
}
