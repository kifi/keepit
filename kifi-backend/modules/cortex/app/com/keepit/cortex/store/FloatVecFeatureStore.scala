package com.keepit.cortex.store

import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.FloatVecFeature
import com.keepit.common.store._

import java.io._

trait FloatVecFeatureStore[K, T, M <: StatModel] extends ObjectStore[VersionedStoreKey[K, M], FloatVecFeature[T, M]]

trait S3BlobFloatVecFeatureStore[K, T, M <: StatModel] extends VersionedS3Store[K, M, FloatVecFeature[T, M]]
  with S3BlobStore[VersionedStoreKey[K, M], FloatVecFeature[T, M]]{

  def encodeValue(feature: FloatVecFeature[T, M]): Array[Byte] = {
    StoreUtil.FloatArrayFormmater.toBinary(feature.vectorize)
  }

  def decodeValue(data: Array[Byte]): FloatVecFeature[T, M] = {
    val arr = StoreUtil.FloatArrayFormmater.fromBinary(data)
    FloatVecFeature[T, M](arr)
  }
}

class InMemoryFloatVecFeatureStore[K, T, M <: StatModel] extends VersionedInMemoryStore[K, M, FloatVecFeature[T, M]]
  with FloatVecFeatureStore[K, T, M]
