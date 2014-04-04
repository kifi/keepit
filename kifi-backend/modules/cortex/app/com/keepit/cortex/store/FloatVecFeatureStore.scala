package com.keepit.cortex.store

import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.FloatVecFeature
import com.keepit.common.store._

import java.io._

trait FloatVecFeatureStore[K, T, M <: StatModel] extends ObjectStore[VersionedStoreKey[K, M], FloatVecFeature[T, M]]

trait S3BlobFloatVecFeatureStore[K, T, M <: StatModel] extends VersionedS3Store[K, M, FloatVecFeature[T, M]]
  with S3BlobStore[VersionedStoreKey[K, M], FloatVecFeature[T, M]]{

  def encodeValue(feature: FloatVecFeature[T, M]): Array[Byte] = {
    val bs = new ByteArrayOutputStream(feature.value.size * 4)
    val os = new DataOutputStream(bs)
    feature.value.foreach{os.writeFloat}
    os.close()
    val rv = bs.toByteArray()
    bs.close()
    rv
  }

  def decodeValue(data: Array[Byte]): FloatVecFeature[T, M] = {
    val is = new DataInputStream(new ByteArrayInputStream(data))
    val N = data.size / 4
    val arr = new Array[Float](N)
    var n = 0
    while ( n < N ){
      arr(n) = is.readFloat()
      n += 1
    }
    is.close()
    FloatVecFeature[T, M](arr)
  }
}

class InMemoryFloatVecFeatureStore[K, T, M <: StatModel] extends VersionedInMemoryStore[K, M, FloatVecFeature[T, M]]
  with FloatVecFeatureStore[K, T, M]
