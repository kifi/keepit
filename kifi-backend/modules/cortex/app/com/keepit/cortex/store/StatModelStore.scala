package com.keepit.cortex.store

import com.keepit.common.store.ObjectStore
import com.keepit.common.store.S3BlobStore
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.BinaryFormatter
import com.keepit.common.store.InMemoryBlobStore

trait StatModelStore[M <: StatModel] extends ObjectStore[ModelVersion[M], M]

trait S3StatModelStore[M <: StatModel] extends S3BlobStore[ModelVersion[M], M] with StatModelStore[M] {
  protected val formatter: BinaryFormatter[M]
  protected val prefix: String
  override def keyPrefix() = prefix
  protected def encodeValue(model: M): Array[Byte] = formatter.toBinary(model)
  protected def decodeValue(bytes: Array[Byte]): M = formatter.fromBinary(bytes)
  override def -=(key: ModelVersion[M]): this.type = throw new UnsupportedOperationException // don't delete model
  protected def idToKey(id: ModelVersion[M]): String = "%s%s%s.bin".format(keyPrefix, "version_", id.toString)

}

trait InMemoryStatModelStore[M <: StatModel] extends InMemoryBlobStore[ModelVersion[M], M] with StatModelStore[M] {
  protected val formatter: BinaryFormatter[M]
  protected def packValue(model: M): Array[Byte] = formatter.toBinary(model)
  protected def unpackValue(bytes: Array[Byte]): M = formatter.fromBinary(bytes)
  override def -=(key: ModelVersion[M]): this.type = throw new UnsupportedOperationException
}
