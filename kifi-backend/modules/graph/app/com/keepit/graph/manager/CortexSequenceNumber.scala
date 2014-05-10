package com.keepit.graph.manager

import com.keepit.cortex.core.{ModelVersion, StatModel}
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.models.lda.{LDATopic, DenseLDA}


case class CortexSequenceNumber[M <: StatModel, T](modelVersion: ModelVersion[M], seq: SequenceNumber[T]) {
  def toLong: Long = CortexSequenceNumber.toLong(this)
}

object CortexSequenceNumber {
  // top 8 bits reserved for version
  val versionSpace = 8
  val seqSpace = 56
  val maxSeq = (1L << seqSpace) - 1
  val maxVersion = (1 << versionSpace) - 1

  def toLong[M <: StatModel, T](cortexSeq: CortexSequenceNumber[M, T]) = {
    require(cortexSeq.seq.value <= maxSeq, s"Sequence number ${cortexSeq.seq} is too large.")
    require(cortexSeq.modelVersion.version <= maxVersion, s"Model version number ${cortexSeq.modelVersion} is too large.")
    cortexSeq.modelVersion.version.toLong << seqSpace | cortexSeq.seq.value
  }

  def fromLong[M <: StatModel, T](vSeq: Long): CortexSequenceNumber[M, T] = {
    val seq = SequenceNumber[T](vSeq & maxSeq)
    val version = ModelVersion[M]((vSeq >> seqSpace).toInt)
    CortexSequenceNumber(version, seq)
  }
}

case class LDATopicId(version: ModelVersion[DenseLDA], topic: LDATopic)  {
  def toLong: Long = LDATopicId.toLong(this)
}

object LDATopicId {
  // 32 bits for topic, 24 bits for version, 8 bits left for type tag,

  val versionSpace = 24
  val topicSpace = 32
  val maxTopic = (1L << topicSpace) - 1
  val maxVersion = (1L << versionSpace) - 1

  def toLong(versionedTopicId: LDATopicId): Long = {
    require(versionedTopicId.topic.index <= maxTopic, s"Topic ${versionedTopicId.topic} is too large.")
    require(versionedTopicId.version.version <= maxVersion, s"Version number ${versionedTopicId.version} is too large.")
    versionedTopicId.version.version.toLong << topicSpace | versionedTopicId.topic.index.toLong
  }
  def versionFromLong(topicId: Long): ModelVersion[DenseLDA] = ModelVersion[DenseLDA]((topicId >> topicSpace).toInt)
  def topicFromLong(topicId: Long): LDATopic = LDATopic((topicId & maxTopic).toInt)

  def fromLong(topicId: Long) = LDATopicId(versionFromLong(topicId), topicFromLong(topicId))
}
