package com.keepit.search.tracking

import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ ObjectStore, S3BlobStore, InMemoryObjectStore, S3Bucket }
import com.amazonaws.services.s3.AmazonS3
import java.nio.ByteBuffer

case class FullFilterChunkId(name: String, chunk: Int)

trait ProbablisticLRUStore extends ObjectStore[FullFilterChunkId, Array[Int]]

class S3ProbablisticLRUStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog) extends S3BlobStore[FullFilterChunkId, Array[Int]] with ProbablisticLRUStore {

  protected def idToKey(id: FullFilterChunkId): String = id.name + "/chunk_" + id.chunk.toString

  protected def encodeValue(value: Array[Int]): Array[Byte] = {
    val byteBuffer = ByteBuffer.allocate(value.size * 4)
    byteBuffer.asIntBuffer.put(value.array)
    byteBuffer.array
  }

  protected def decodeValue(data: Array[Byte]): Array[Int] = {
    val intBuffer = ByteBuffer.wrap(data).asIntBuffer
    val outArray = new Array[Int](data.length / 4)
    intBuffer.get(outArray)
    outArray
  }

}

class InMemoryProbablisticLRUStoreImpl extends InMemoryObjectStore[FullFilterChunkId, Array[Int]] with ProbablisticLRUStore
