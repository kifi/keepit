package com.keepit.common.store

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.inject._
import com.amazonaws.auth._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.S3Object
import play.api.libs.json.Json
import play.api.libs.json.Format
import play.api.Play
import play.api.Play.current
import java.io.InputStream
import java.io.ByteArrayInputStream

case class S3Bucket(name: String) 

trait S3ObjectStore[A, B]  extends ObjectStore[A, B] with Logging {

  val bucketName: S3Bucket
  val amazonS3Client: AmazonS3
  val formatter: Format[B]
  
  private val ENCODING = "UTF-8"
  
  implicit def bucketName(bucket: S3Bucket): String = bucket.name
  
  private def keyPrefix: String = Play.isDev match {
    case true => System.getProperty("user.name") + "_"
    case false => ""
  }
  
  private def idToBJsonKey(id: Id[A]): String = "%s%s.json".format(keyPrefix, id.id)
  
  def += (kv: (Id[A], B)) = {
    kv match {
      case (key, value) =>
        doWithS3Client("adding an item to S3Store"){ s3Client =>
          val metadata = new ObjectMetadata()
          metadata.setContentEncoding(ENCODING)
          metadata.setContentType("application/json")
          Some(s3Client.putObject(bucketName, 
              idToBJsonKey(key), 
              toInputStream(value), 
              metadata))
        }
    }
    this
  }
  
  def -= (key: Id[A]) = {
    doWithS3Client("removing an item from S3BStore"){ s3Client =>
      Some(s3Client.deleteObject(bucketName, idToBJsonKey(key)))
    }
    this
  }
  
  def get(id: Id[A]): Option[B] = {
    doWithS3Client("getting an item from S3BStore"){ s3Client =>
      val key = idToBJsonKey(id)
      val s3obj = try {
        Some(s3Client.getObject(bucketName, key)) 
      } catch {
        case e: AmazonS3Exception if (e.getMessage().contains("The specified key does not exist")) => None
      } 
      s3obj map extractValue
    }
  }
  
  private def extractValue(s3obj: S3Object) = {
    val is = s3obj.getObjectContent
    try {
      val jsonString = scala.io.Source.fromInputStream(is, ENCODING).getLines().mkString("\n")
      val json = Json.parse(jsonString)
      formatter.reads(json)
    } finally {
      is.close
    }
  } 
  
  private def doWithS3Client[T](what: =>String)(body: AmazonS3 => Option[T]): Option[T] = try {
    body(amazonS3Client) 
  } catch {
    case ex: Exception =>
      log.error("failed: " + what , ex)
      throw ex
  }
  
  private def toInputStream(B: B): InputStream = new ByteArrayInputStream(formatter.writes(B).toString().getBytes(ENCODING))
  
}