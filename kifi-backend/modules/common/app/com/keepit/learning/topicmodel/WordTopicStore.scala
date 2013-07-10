package com.keepit.learning.topicmodel

import com.amazonaws.services.s3._
import com.keepit.common.store.S3ObjectStore
import com.keepit.common.store.S3Bucket
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.S3Object
import java.io.InputStream
import com.keepit.common.strings._
import com.keepit.common.store.InMemoryObjectStore
import com.keepit.common.store.ObjectStore
import scala.io.Source

// a plain txt file store, fileName -> fileContent
trait WordTopicStore extends ObjectStore[String, String]

class S3WordTopicStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3) extends S3ObjectStore[String, String] with WordTopicStore {
  val prefix = "word_topic/"              // S3 folder

  def unpackValue(s3Obj : S3Object) : String = {
    val is = s3Obj.getObjectContent
    try {
      val jsonString = Source.fromInputStream(is, UTF8).getLines().mkString("\n")
      jsonString                         // will convert the string to model elsewhere
    } finally {
      is.close
    }
  }
  def packValue(value : String) : (InputStream, ObjectMetadata) = {
    throw new NotImplementedError       // currently the model is provided outside Scala. We are not supposed to write to the bucket.
  }

  def idToKey(id: String) = prefix + "%s.json".format(id)
}

class InMemoryWordTopicStoreImpl extends InMemoryObjectStore[String, String] with WordTopicStore
