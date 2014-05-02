package com.keepit.cortex.models.lda

import com.keepit.cortex.core.ModelVersion

case class LDATopicId(id: Int) extends AnyVal
case class VersionedLDATopicId(id: Long) extends AnyVal

object VersionedLDATopicId {
  // 8 bits for type tag, 8 bits for version, 48 bits left for id

  def apply(version: Int, unversionedId: Int): VersionedLDATopicId = VersionedLDATopicId(version.toLong << 48 | unversionedId.toLong)
  def getVersion(versionedId: Long): ModelVersion[DenseLDA] = ModelVersion[DenseLDA]((versionedId >> 48).toInt)
  def getUnversionedId(versionedId: Long): LDATopicId = LDATopicId((~(0xFFL << 48) & versionedId).toInt)
}
