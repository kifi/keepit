package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.store.{ObjectStore, S3BlobStore, InMemoryObjectStore, S3Bucket}

import com.amazonaws.services.s3.AmazonS3

import java.nio.{IntBuffer, ByteBuffer}


trait ProbablisticLRUStore extends ObjectStore[Id[IntBuffer], IntBuffer]

class S3ProbablisticLRUStoreImpl(val bucketName: S3Bucket, val filterName: ProbablisticLRUName, val amazonS3Client: AmazonS3) extends S3BlobStore[Id[IntBuffer], IntBuffer] with ProbablisticLRUStore {

    protected def idToKey(id: Id[IntBuffer]) = filterName.name + "/chunk_" + id.toString

    protected def encodeValue(value: IntBuffer) : Array[Byte] = {
        value.compact
        val byteBuffer = ByteBuffer.allocate(value.array.size*4)
        byteBuffer.asIntBuffer.put(value.array)
        byteBuffer.array
    }

    protected def decodeValue(data: Array[Byte]) : IntBuffer = ByteBuffer.wrap(data).asIntBuffer

}



class InMemoryProbablisticLRUStoreImpl extends InMemoryObjectStore[Id[IntBuffer], IntBuffer] with ProbablisticLRUStore
