package com.keepit.typeahead

import com.keepit.common.db.Id
import com.keepit.common.store._
import com.keepit.common.logging.AccessLog
import com.amazonaws.services.s3.AmazonS3
import java.nio.ByteBuffer
import com.amazonaws.services.s3.model.ObjectMetadata
import com.keepit.common.store.S3Bucket

trait PrefixFilterStore[T] extends ObjectStore[Id[T], Array[Long]] with MetadataAccess[Id[T], Array[Long]]

class S3PrefixFilterStoreImpl[T](val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog) extends S3BlobStore[Id[T], Array[Long]] with PrefixFilterStore[T] {

  protected def idToKey(id: Id[T]) = "id_" + id.toString

  protected def encodeValue(value: Array[Long]): Array[Byte] = {
    val byteBuffer = ByteBuffer.allocate(value.length * 8)
    byteBuffer.asLongBuffer.put(value)
    byteBuffer.array
  }

  protected def decodeValue(data: Array[Byte]): Array[Long] = {
    val intBuffer = ByteBuffer.wrap(data).asLongBuffer
    val outArray = new Array[Long](data.length / 8)
    intBuffer.get(outArray)
    outArray
  }
}

class InMemoryPrefixFilterStoreImpl[T] extends InMemoryObjectStore[Id[T], Array[Long]] with PrefixFilterStore[T]

