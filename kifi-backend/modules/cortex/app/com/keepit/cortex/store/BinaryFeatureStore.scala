package com.keepit.cortex.store

import com.keepit.cortex.core.StatModel
import com.keepit.common.store._
import com.keepit.cortex.core.FeatureRepresentation
import java.io._
import com.keepit.cortex.core.BinaryFeatureFormatter

trait BinaryFeatureStore[K, T, M <: StatModel, FT <: FeatureRepresentation[T, M]]
  extends ObjectStore[VersionedStoreKey[K, M], FT] with VersionedStore[K, M, FT]

trait S3BlobBinaryFeatureStore[K, T, M <: StatModel, FT <: FeatureRepresentation[T, M]] extends VersionedS3Store[K, M, FT]
    with S3BlobStore[VersionedStoreKey[K, M], FT] with BinaryFeatureStore[K, T, M, FT] {

  val formatter: BinaryFeatureFormatter[FT]

  def encodeValue(feature: FT): Array[Byte] = {
    formatter.toBinary(feature)
  }

  def decodeValue(data: Array[Byte]): FT = {
    formatter.fromBinary(data)
  }

  protected val prefix: String
  override def keyPrefix() = prefix
  override def idToKey(id: VersionedStoreKey[K, M]): String = keyPrefix + id.toKey
}

class InMemoryBinaryFeatureStore[K, T, M <: StatModel, FT <: FeatureRepresentation[T, M]]
  extends VersionedInMemoryStore[K, M, FT] with BinaryFeatureStore[K, T, M, FT]
