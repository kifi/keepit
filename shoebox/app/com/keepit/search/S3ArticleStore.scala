package com.keepit.search

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import scala.collection.mutable.{Map => MutableMap}
import com.amazonaws.auth._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import java.io.{InputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.lang.UnsupportedOperationException


trait ArticleStore extends MutableMap[Id[NormalizedURI], Article]

case class S3Bucket(name: String) 

class S3ArticleStoreImpl(bucketName: S3Bucket, amazonS3Client: AmazonS3) extends ArticleStore with Logging {

  implicit def bucketName(bucket: S3Bucket): String = bucket.name
  
  private def idToArticleJsonKey(id: Id[NormalizedURI]): String = "%s.json".format(id.id)
  
  def += (kv: (Id[NormalizedURI], Article)) = {
    kv match {
      case (normalizedUrlId, article) =>
        doWithS3Client("adding an item to S3ArticleStore"){ s3Client =>
          s3Client.putObject(bucketName, idToArticleJsonKey(normalizedUrlId), toInputStream(article.asInstanceOf[Article]), new ObjectMetadata)
        }
    }
    this
  }
  
  def -= (normalizedUrlId: Id[NormalizedURI]) = {
    doWithS3Client("removing an item from S3ArticleStore"){ s3Client =>
      s3Client.deleteObject(bucketName, idToArticleJsonKey(normalizedUrlId))
    }
    this
  }
  
  def get(normalizedUrlId: Id[NormalizedURI]): Option[Article] = {
    doWithS3Client("getting an item from S3ArticleStore"){ s3Client =>
      val s3obj = s3Client.getObject(bucketName, idToArticleJsonKey(normalizedUrlId))
      val is = s3obj.getObjectContent
      val ois = new ObjectInputStream(is)
      try {
        ois.readObject.asInstanceOf[Article]
      } finally {
        is.close
        ois.close
      }
    }
  }
  
  def iterator = throw new UnsupportedOperationException
  
  override def empty = throw new UnsupportedOperationException
  
  private def doWithS3Client[T](what: =>String)(body: AmazonS3=>T): Option[T] = {
    var ret: Option[T] = None
    try {
      ret = Some(body(amazonS3Client))
    } catch {
      case ex: Exception =>
        log.error("failed: "+what, ex)
        throw ex
    }
    ret
  }
  
  private def toInputStream(article: Article): InputStream = {
    val totalStringSize = article.title.length + article.content.length
    val buf = new SerializationBuffer(totalStringSize * 2 + 100)
    val os = new ObjectOutputStream(buf)
    os.writeObject(article)
    os.close
    buf.getInputStream
  }
  
  private class SerializationBuffer(initialSize: Int) extends ByteArrayOutputStream(initialSize) {
    def getInputStream = new ByteArrayInputStream(buf, 0, count)
  }
}