package com.keepit.cortex.store

import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.FloatVecFeature
import com.keepit.common.store._
import java.io._
import com.keepit.cortex.core.FeatureRepresentation

trait FloatVecFeatureStore[K, T, M <: StatModel] extends ObjectStore[VersionedStoreKey[K, M], FeatureRepresentation[T, M]] with VersionedStore[K, M, FeatureRepresentation[T, M]]

trait S3BlobFloatVecFeatureStore[K, T, M <: StatModel] extends VersionedS3Store[K, M, FeatureRepresentation[T, M]]
    with S3BlobStore[VersionedStoreKey[K, M], FeatureRepresentation[T, M]] with FloatVecFeatureStore[K, T, M] {

  def encodeValue(feature: FeatureRepresentation[T, M]): Array[Byte] = {
    StoreUtil.FloatArrayFormmater.toBinary(feature.vectorize)
  }

  def decodeValue(data: Array[Byte]): FeatureRepresentation[T, M] = {
    val arr = StoreUtil.FloatArrayFormmater.fromBinary(data)
    FloatVecFeature[T, M](arr)
  }

  protected val prefix: String
  override def keyPrefix() = prefix
  override def idToKey(id: VersionedStoreKey[K, M]): String = keyPrefix + id.toKey
}

class InMemoryFloatVecFeatureStore[K, T, M <: StatModel] extends VersionedInMemoryStore[K, M, FeatureRepresentation[T, M]]
  with FloatVecFeatureStore[K, T, M]
