package com.keepit.graph.manager

import com.keepit.cortex.core.{ ModelVersion, StatModel }
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }

case class CortexSequenceNumber[M <: StatModel, T](modelVersion: ModelVersion[M], seq: SequenceNumber[T]) {
  def toLong: Long = CortexSequenceNumber.toLong(this)
  override def toString = modelVersion + "::" + seq
}

object CortexSequenceNumber {
  val totalSpace = 63 // To ensure non-negative CortexSequenceNumbers
  val versionSpace = 8
  val seqSpace = 55
  val maxSeq: Long = (1L << seqSpace) - 1
  val maxVersion: Long = (1L << versionSpace) - 1

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

case class LDATopicId(version: ModelVersion[DenseLDA], topic: LDATopic) {
  def toLong: Long = LDATopicId.toLong(this)
  override def toString = version + "::" + topic
}

object LDATopicId {
  // 31 bits for topic, 24 bits for version, 8 bits left for type tag,

  val totalSpace = 55 // To be turned into VertexIds
  val versionSpace = 24
  val topicSpace = 31 // assuming non-negative Topics
  val maxTopic: Long = (1L << topicSpace) - 1
  val maxVersion: Long = (1L << versionSpace) - 1

  def toLong(topicId: LDATopicId): Long = {
    require(topicId.topic.index <= maxTopic, s"Topic ${topicId.topic} is too large.")
    require(topicId.version.version <= maxVersion, s"Version number ${topicId.version} is too large.")
    topicId.version.version.toLong << topicSpace | topicId.topic.index.toLong
  }
  def versionFromLong(topicId: Long): ModelVersion[DenseLDA] = ModelVersion[DenseLDA]((topicId >> topicSpace).toInt)
  def topicFromLong(topicId: Long): LDATopic = LDATopic((topicId & maxTopic).toInt)

  def fromLong(topicId: Long) = LDATopicId(versionFromLong(topicId), topicFromLong(topicId))
}
