package com.keepit.common.store

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.strings._
import com.keepit.inject._

import com.amazonaws.auth._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.S3Object

import org.apache.poi.util.IOUtils

import play.api.libs.json.Json
import play.api.libs.json.Format
import play.api.Play
import play.api.Play.current
import play.api.Logger

import java.io._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.serializer.BinaryFormat
import java.util.zip.{GZIPOutputStream, GZIPInputStream}
import java.nio.file.Files

case class S3Bucket(name: String)

trait S3ObjectStore[A, B]  extends ObjectStore[A, B] with Logging {

  val bucketName: S3Bucket
  val amazonS3Client: AmazonS3

  protected def unpackValue(s3Obj : S3Object) : B
  protected def packValue(value : B) : (InputStream, ObjectMetadata)
  protected def idToKey(id: A) : String

  implicit def bucketName(bucket: S3Bucket): String = bucket.name

  protected def keyPrefix: String = Play.isDev match {
    case true => System.getProperty("user.name") + "_"
    case false => ""
  }

  protected def doWithS3Client[T](what: =>String)(body: AmazonS3 => Option[T]): Option[T] = try {
    body(amazonS3Client)
  } catch {
    case ex: Exception =>
      log.error("failed: " + what , ex)
      throw ex
  }

  private lazy val accessLog = Logger("com.keepit.access")

  def += (kv: (A, B)) = {
    val startTime = System.currentTimeMillis
    kv match {
      case (key, value) =>
        doWithS3Client("adding an item to S3Store"){ s3Client =>
          val (inputStream, metadata) = packValue(value)
          try {
            Some(s3Client.putObject(bucketName,
              idToKey(key),
              inputStream,
              metadata))
          } catch {
            case ase: AmazonServiceException =>
              val error = """Error Message: %s    " + );
                             HTTP Status Code: %s
                             AWS Error Code: %s
                             Error Type: %s
                             Request ID: %s""".format(
                  ase.getMessage(), ase.getStatusCode(), ase.getErrorCode(), ase.getErrorType(), ase.getRequestId())
              throw new Exception("could not send object key: [%s]\nvalue: [%s]\nto bucket %s: %s".format(key, value, bucketName, error), ase)
            case e: Exception =>
              throw new Exception("could not send object key: [%s]\nvalue: [%s]\nto bucket %s".format(key, value, bucketName), e)
          } finally {
            try { inputStream.close() } catch {case e: Exception => log.error("error closing content stream.", e)}
          }
        }
      val millis = System.currentTimeMillis - startTime
      accessLog.info(s"""[S3] [${bucketName.name}] PUT $key took [${millis}ms]""")
    }
    this
  }

  def -= (key: A) = {
    val startTime = System.currentTimeMillis
    doWithS3Client("removing an item from S3BStore"){ s3Client =>
      Some(s3Client.deleteObject(bucketName, idToKey(key)))
    }
    val millis = System.currentTimeMillis - startTime
    accessLog.info(s"""[S3] [${bucketName.name}] DEL $key took [${millis}ms]""")
    this
  }

  def get(id: A): Option[B] = {
    val startTime = System.currentTimeMillis
    doWithS3Client("getting an item from S3BStore"){ s3Client =>
      val key = idToKey(id)
      val s3obj = try {
        Some(s3Client.getObject(bucketName, key))
      } catch {
        case e: AmazonS3Exception if (e.getMessage().contains("The specified key does not exist")) => None
      }
      val value = s3obj map unpackValue
      val millis = System.currentTimeMillis - startTime
      accessLog.info(s"""[S3] [${bucketName.name}] GET $key took [${millis}ms]""")
      value
    }
  }

}

trait S3JsonStore[A,B] extends S3ObjectStore[A, B] {

  protected val formatter: Format[B]

  protected def idToKey(id: A): String = "%s%s.json".format(keyPrefix, id.toString)


  protected def packValue(value: B) = {
    val metadata = new ObjectMetadata()
    metadata.setContentEncoding(UTF8)
    metadata.setContentType("application/json")
    val content = formatter.writes(value).toString().getBytes(UTF8)
    metadata.setContentLength(content.length)
    val inputStream = new ByteArrayInputStream(content)
    (inputStream, metadata)
  }

  protected def unpackValue(s3obj: S3Object) = {
    val is = s3obj.getObjectContent
    try {
      val jsonString = scala.io.Source.fromInputStream(is, UTF8).getLines().mkString("\n")
      val json = Json.parse(jsonString)
      formatter.reads(json).get
    } finally {
      is.close
    }
  }

}

trait S3BlobStore[A,B] extends S3ObjectStore[A,B] {

  protected def encodeValue(value: B) : Array[Byte]

  protected def decodeValue(data: Array[Byte]) : B

  protected def packValue(value: B) = {
    val content = encodeValue(value)
    val metadata = new ObjectMetadata()
    metadata.setContentType("application/octet-stream")
    metadata.setContentLength(content.length)
    val inputStream = new ByteArrayInputStream(content)
    (inputStream, metadata)
  }

  protected def unpackValue(s3Obj: S3Object) = {
      val size  = s3Obj.getObjectMetadata().getContentLength()
      val dataStream  = s3Obj.getObjectContent()
      try{
        decodeValue(IOUtils.toByteArray(dataStream))
      }
      finally {
        dataStream.close()
      }

  }

}

trait BlobFormat[B] {
  val format: BinaryFormat[B]
  protected def encodeValue(value: B) : Array[Byte] = format.writes(Some(value))
  protected def decodeValue(data: Array[Byte]) : B = format.reads(data).get
}

trait S3FileStore[A] extends S3ObjectStore[A, File] {

  val inbox: File
  if (!inbox.exists()) inbox.mkdirs()
  require(inbox.isDirectory, s"$inbox is not a directory.")

  protected def packValue(value: File) = {
    val metadata = new ObjectMetadata()
    metadata.setContentType(Files.probeContentType(value.toPath))
    metadata.setContentLength(value.length())
    metadata.addUserMetadata("name", value.getName)

    val fileStream = new FileInputStream(value)
    val compressedStream = new CompressedInputStream(fileStream)

    (compressedStream, metadata)
  }

  protected def unpackValue(s3obj: S3Object) = {
    val metadata = s3obj.getObjectMetadata
    val name = metadata.getUserMetadata.get("name")
    val file = new File(inbox, name)
    val uncompressedStream = new GZIPInputStream(s3obj.getObjectContent)
    Files.copy(uncompressedStream, file.toPath)
    uncompressedStream.close()
    file
  }

  private class CompressedInputStream(inputStream: InputStream) extends InputStream {
    val toPipe = new PipedOutputStream()
    val gzipOutputStream = new GZIPOutputStream(toPipe)
    val fromPipe = new PipedInputStream(toPipe)

    def read(): Int = {
      gzipOutputStream.write(inputStream.read())
      fromPipe.read()
    }
  }
}

trait S3Module extends ScalaModule

