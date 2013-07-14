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
import com.keepit.common.store.S3BlobStore
import java.io.{DataOutputStream, DataInputStream, ByteArrayInputStream, ByteArrayOutputStream}
import play.api.libs.json._
import com.keepit.common.store.S3JsonStore

/**
 * This file contains various S3 stores related to topic model
 */


/**
 *  a plain txt file store, fileName -> fileContent
 *  file content format: {"w1":[1,2],"w2":[3,4],"w3":[5,6]}
 */

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



/**
 * Let V = size of vocabulary, let T = num of topics. We represent the
 * word -> topic matrix as a V*T double array
 */
trait WordTopicBlobStore extends ObjectStore[String, Array[Double]]

class S3WordTopicBlobStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3) extends S3BlobStore[String, Array[Double]] with WordTopicBlobStore {
  val prefix = "word_topic/"

  def encodeValue(arr: Array[Double]): Array[Byte] = {
    val bs = new ByteArrayOutputStream(arr.size * 8)
    val os = new DataOutputStream(bs)
    arr.foreach{os.writeDouble}
    os.close()
    val rv = bs.toByteArray()
    bs.close()
    rv
  }

  def decodeValue(data: Array[Byte]): Array[Double] = {
    val is = new DataInputStream(new ByteArrayInputStream(data))
    val N = data.size / 8
    val arr = (0 until N).map{i => is.readDouble()}
    is.close()
    arr.toArray
  }

  def idToKey(id: String) = prefix + "%s.topicVector.bin".format(id)
}

class InMemoryWordTopicBlobStoreImpl extends InMemoryObjectStore[String, Array[Double]] with WordTopicBlobStore


/**
 * store words
 */
trait WordStore extends ObjectStore[String, Array[String]]

class StringArraryFormat extends Format[Array[String]] {
  def writes(arr: Array[String]): JsValue = {
    JsArray(arr.map{JsString(_)}.toSeq)
  }

  def reads(js: JsValue): JsResult[Array[String]] = {
    JsSuccess(js.as[JsArray].value.map{_.as[JsString].value}.toArray)
  }
}

class S3WordStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val formatter: Format[Array[String]] = new StringArraryFormat())
  extends S3JsonStore[String, Array[String]] with WordStore {
  val prefix = "word_topic/"
  override def idToKey(id: String) = prefix + "%s.words.json".format(id)
}

class InMemoryWordStoreImpl extends InMemoryObjectStore[String, Array[String]] with WordStore

/**
 * show top words related to topics
 */
trait TopicWordsStore extends ObjectStore[String, String]

class S3TopicWordsStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3) extends S3ObjectStore[String, String] with TopicWordsStore {
   val prefix = "word_topic/"

  def unpackValue(s3Obj : S3Object) : String = {
    val is = s3Obj.getObjectContent
    try {
      val content = Source.fromInputStream(is, UTF8).getLines().mkString("\n")
      content
    } finally {
      is.close
    }
  }
  def packValue(value : String) : (InputStream, ObjectMetadata) = {
    throw new NotImplementedError       // currently this file is provided outside Scala. We are not supposed to write to the bucket.
  }

  def idToKey(id: String) = prefix + "%s.topicWords.txt".format(id)
}

class InMemoryTopicWordsStoreImpl extends InMemoryObjectStore[String, String] with TopicWordsStore
