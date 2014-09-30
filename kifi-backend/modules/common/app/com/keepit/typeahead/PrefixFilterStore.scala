package com.keepit.typeahead

import com.keepit.common.db.Id
import com.keepit.common.store._
import com.keepit.common.logging.AccessLog
import com.amazonaws.services.s3.AmazonS3
import java.nio.ByteBuffer
import com.amazonaws.services.s3.model.ObjectMetadata
import com.keepit.common.store.S3Bucket

trait PrefixFilterStore[T, E] extends ObjectStore[Id[T], PrefixFilter[E]] with MetadataAccess[Id[T], PrefixFilter[E]]

class S3PrefixFilterStoreImpl[T, E](val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog) extends S3BlobStore[Id[T], PrefixFilter[E]] with PrefixFilterStore[T, E] {

  protected def idToKey(id: Id[T]) = "id_" + id.toString

  protected def encodeValue(value: PrefixFilter[E]): Array[Byte] = PrefixFilter.toByteArray(value)

  protected def decodeValue(data: Array[Byte]): PrefixFilter[E] = PrefixFilter.fromByteArray(data)
}

class InMemoryPrefixFilterStoreImpl[T, E] extends InMemoryObjectStore[Id[T], PrefixFilter[E]] with PrefixFilterStore[T, E]

