package com.keepit.search

import scala.collection.mutable.{Map => MutableMap}
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.serializer.ArticleSerializer
import com.keepit.inject._
import play.api.Play.current
import com.amazonaws.auth._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import java.io.{InputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.lang.UnsupportedOperationException
import play.api.libs.json.Json
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.S3Object


trait ArticleStore extends MutableMap[Id[NormalizedURI], Article]

case class S3Bucket(name: String) 

class S3ArticleStoreImpl(bucketName: S3Bucket, amazonS3Client: AmazonS3) extends ArticleStore with Logging {
  
  private val ENCODING = "UTF-8"
  
  implicit def bucketName(bucket: S3Bucket): String = bucket.name      
  
  private def idToArticleJsonKey(id: Id[NormalizedURI]): String = "%s.json".format(id.id)
  
  def += (kv: (Id[NormalizedURI], Article)) = {
    kv match {
      case (normalizedUrlId, article) =>
        doWithS3Client("adding an item to S3ArticleStore"){ s3Client =>
          val metadata = new ObjectMetadata()
          metadata.setContentEncoding(ENCODING)
          metadata.setContentType("application/json")
          Some(s3Client.putObject(bucketName, 
              idToArticleJsonKey(normalizedUrlId), 
              toInputStream(article), 
              metadata))
        }
    }
    this
  }
  
  def -= (normalizedUrlId: Id[NormalizedURI]) = {
    doWithS3Client("removing an item from S3ArticleStore"){ s3Client =>
      Some(s3Client.deleteObject(bucketName, idToArticleJsonKey(normalizedUrlId)))
    }
    this
  }
  
  def get(normalizedUrlId: Id[NormalizedURI]): Option[Article] = {
    doWithS3Client("getting an item from S3ArticleStore"){ s3Client =>
      val key = idToArticleJsonKey(normalizedUrlId)
      val s3obj = try {
        Some(s3Client.getObject(bucketName, key)) 
      } catch {
        case e: AmazonS3Exception if (e.getMessage().contains("The specified key does not exist")) => None
      } 
      s3obj map extractArticle
    }
  }
  
  private def extractArticle(s3obj: S3Object) = {
    val is = s3obj.getObjectContent
    try {
      val jsonString = scala.io.Source.fromInputStream(is, ENCODING).getLines().mkString("\n")
      val json = Json.parse(jsonString)
      inject[ArticleSerializer].reads(json)
    } finally {
      is.close
    }
  } 
  
  def iterator = throw new UnsupportedOperationException
  
  override def empty = throw new UnsupportedOperationException
  
  private def doWithS3Client[T](what: =>String)(body: AmazonS3 => Option[T]): Option[T] = {
    var ret: Option[T] = None
    try {
      ret = body(amazonS3Client) 
    } catch {
      case ex: Exception =>
        log.error("failed: " + what , ex)
        throw ex
    }
    ret
  }
  
  private def toInputStream(article: Article): InputStream = 
    new ByteArrayInputStream(inject[ArticleSerializer].writes(article).toString().getBytes(ENCODING))
  
}