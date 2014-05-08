package com.keepit.cortex.lda


case class VersionedLDATopicId(id: Long) extends AnyVal

object VersionedLDATopicId {
  // 8 bits for type tag, 8 bits for version, 48 bits left for id

  def apply(version: Int, unversionedId: Int): VersionedLDATopicId = VersionedLDATopicId(version.toLong << 48 | unversionedId.toLong)
  def getVersion(versionedId: Long): Int = (versionedId >> 48).toInt
  def getUnversionedId(versionedId: Long): Int = (~(0xFFL << 48) & versionedId).toInt
}
