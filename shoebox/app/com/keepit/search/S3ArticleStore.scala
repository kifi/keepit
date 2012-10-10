package com.keepit.search

import scala.collection.Map
import com.amazonaws.auth._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import java.io.{InputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.lang.UnsupportedOperationException

class S3ArticleStore(bucketName: String, accessKey: String, secretKey: String) extends Map[Long, Article] {
  val awsCredentials = new BasicAWSCredentials(accessKey, secretKey)
  val amazonS3Client = new AmazonS3Client(awsCredentials)

  def + [T >: Article](kv: (Long, T)):Map[Long, T] = {
    kv match {
      case (normalizedUrlId, article) =>
        doWithS3Client{ s3Client =>
          s3Client.putObject(bucketName, normalizedUrlId.toString, toInputStream(article.asInstanceOf[Article]), new ObjectMetadata)
        }
    }
    this
  }
  
  def - (normalizedUrlId: Long) = {
    doWithS3Client{ s3Client =>
      s3Client.deleteObject(bucketName, normalizedUrlId.toString)
    }
    this
  }
  
  def get(normalizedUrlId: Long): Option[Article] = {
    doWithS3Client{ s3Client =>
      val s3obj = s3Client.getObject(bucketName, normalizedUrlId.toString)
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
  
  private def doWithS3Client[T](body: AmazonS3Client=>T): Option[T] = {
    var ret: Option[T] = None
    try {
      ret = Some(body(amazonS3Client))
    } catch {
      case ase: AmazonServiceException =>
        System.out.println("Error Message:    " + ase.getMessage())
        System.out.println("HTTP Status Code: " + ase.getStatusCode())
        System.out.println("AWS Error Code:   " + ase.getErrorCode())
        System.out.println("Error Type:       " + ase.getErrorType())
        System.out.println("Request ID:       " + ase.getRequestId())
      case ace: AmazonClientException =>
        System.out.println("Error Message: " + ace.getMessage())
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