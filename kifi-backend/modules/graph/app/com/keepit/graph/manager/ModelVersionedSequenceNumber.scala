package com.keepit.graph.manager

import com.keepit.cortex.core.{ModelVersion, StatModel}
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.models.lda.DenseLDA


case class ModelVersionedSequenceNumber[M <: StatModel, T](seq: Long) {
  def underlying: SequenceNumber[T] = SequenceNumber(seq & ModelVersionedSequenceNumber.maxUnderlyingSeq)
  def version: ModelVersion[M] = ModelVersion((seq >> ModelVersionedSequenceNumber.versionSpace).toInt)
}

object ModelVersionedSequenceNumber {
  // top 8 bits reserved for version
  val totalSpace = 64
  val versionSpace = 8
  val underlyingSeqSpace = totalSpace - versionSpace
  val maxUnderlyingSeq = (1L << underlyingSeqSpace) - 1
  val maxVersion = (1 << versionSpace) - 1

  def apply[M <: StatModel, T](version: ModelVersion[M], underlying: SequenceNumber[T]): ModelVersionedSequenceNumber[M, T] = {
    require(underlying.value <= maxUnderlyingSeq, s"Underlying sequence number $underlying is too large.")
    require(version.version <= maxVersion, s"Version number $version is too large.")
    val seq = version.version.toLong << underlyingSeqSpace | underlying.value
    ModelVersionedSequenceNumber(seq)
  }
}

import com.keepit.cortex.core.ModelVersion

case class LDATopicId(id: Int) extends AnyVal
case class VersionedLDATopicId(id: Long) extends AnyVal

object VersionedLDATopicId {
  // 8 bits for type tag, 8 bits for version, 48 bits left for id

  def apply(version: ModelVersion[DenseLDA], unversionedId: Int): VersionedLDATopicId = VersionedLDATopicId(version.version.toLong << 48 | unversionedId.toLong)
  def getVersion(versionedId: Long): ModelVersion[DenseLDA] = ModelVersion[DenseLDA]((versionedId >> 48).toInt)
  def getUnversionedId(versionedId: Long): LDATopicId = LDATopicId((~(0xFFL << 48) & versionedId).toInt)
}
