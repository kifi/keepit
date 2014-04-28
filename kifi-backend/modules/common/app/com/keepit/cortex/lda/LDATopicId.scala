package com.keepit.cortex.lda

// id: from 0 to #Topics - 1
case class LDATopicId (version: Int, id: Int)

object LDATopicId {
  // 8 bits for type tag, 8 bits for version, 48 bits left for id
  def toLong(id: LDATopicId): Long = { id.version.toLong << 48 | id.id.toLong }

  def fromLong(x: Long): LDATopicId = {
    val (version, topicId) = (x >> 48, ~(0xFFL << 48) & x)
    LDATopicId(version.toInt, topicId.toInt)
  }
}
