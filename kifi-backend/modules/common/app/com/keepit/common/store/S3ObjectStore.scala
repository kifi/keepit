package com.keepit.common.store

import com.keepit.common.logging.Logging
import com.keepit.common.logging.Access.S3
import com.keepit.common.logging.AccessLog
import com.keepit.common.strings._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.S3Object
import play.api.libs.json._
import play.api.Play.current
import play.api.Play
import java.io._
import com.keepit.serializer.BinaryFormat
import org.apache.commons.io.{ IOUtils, FileUtils }
import com.keepit.common.time._

case class S3Bucket(name: String)

trait S3ObjectStore[A, B] extends ObjectStore[A, B] with MetadataAccess[A, B] with Logging {

  val bucketName: S3Bucket
  val amazonS3Client: AmazonS3
  val accessLog: AccessLog

  protected def unpackValue(s3Obj: S3Object): B
  protected def packValue(value: B): (InputStream, ObjectMetadata)
  protected def idToKey(id: A): String

  implicit def bucketName(bucket: S3Bucket): String = bucket.name

  protected def keyPrefix: String = Play.isDev match {
    case true => System.getProperty("user.name") + "_"
    case false => ""
  }

  protected def doWithS3Client[T](what: => String)(body: AmazonS3 => T): T = try {
    body(amazonS3Client)
  } catch {
    case ex: Exception =>
      log.error("failed: " + what, ex)
      throw ex
  }

  def +=(kv: (A, B)) = {
    kv match {
      case (id, value) =>
        val timer = accessLog.timer(S3)
        val key = idToKey(id)
        doWithS3Client("adding an item to S3Store") { s3Client =>
          val (inputStream, metadata) = packValue(value)
          try {
            s3Client.putObject(bucketName, key, inputStream, metadata)
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
            try { inputStream.close() } catch { case e: Exception => log.error("error closing content stream.", e) }
          }
        }
        accessLog.add(timer.done(space = bucketName.name, key = key, method = "PUT"))
    }
    this
  }

  def -=(id: A) = {
    val timer = accessLog.timer(S3)
    val key = idToKey(id)
    doWithS3Client("removing an item from S3BStore") { s3Client => s3Client.deleteObject(bucketName, key) }
    accessLog.add(timer.done(space = bucketName.name, key = key, method = "DEL"))
    this
  }

  def syncGet(id: A): Option[B] = {
    val timer = accessLog.timer(S3)
    val key = idToKey(id)
    val value = doWithS3Client[Option[B]]("getting an item from S3BStore") { s3Client =>
      val s3obj = try {
        Some(s3Client.getObject(bucketName, key))
      } catch {
        case e: AmazonS3Exception if (e.getMessage().contains("The specified key does not exist")) => None
      }
      s3obj map unpackValue
    }
    accessLog.add(timer.done(space = bucketName.name, key = key, method = "GET"))
    value
  }

  override def getWithMetadata(id: A): Option[(B, Option[ObjMetadata])] = { // WIP; dup code so get() not affected
    val timer = accessLog.timer(S3)
    val key = idToKey(id)
    val t = doWithS3Client[Option[(B, Option[ObjMetadata])]]("getting an item from S3BStore") { s3Client =>
      val s3obj = try {
        Some(s3Client.getObject(bucketName, key))
      } catch {
        case e: AmazonS3Exception if (e.getMessage().contains("The specified key does not exist")) => None
      }
      s3obj map { o =>
        (unpackValue(o), Some(new ObjMetadata {
          def lastModified = o.getObjectMetadata.getLastModified.toDateTime
        }))
      }
    }
    accessLog.add(timer.done(space = bucketName.name, key = key.toString, method = "GET"))
    t
  }

}

class S3ObjectJsonParsinException(message: String) extends Exception(message)

trait S3JsonStore[A, B] extends S3ObjectStore[A, B] {

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

  protected def unpackValue(s3obj: S3Object): B = {
    val is = s3obj.getObjectContent
    try {
      val jsonString = scala.io.Source.fromInputStream(is, UTF8).getLines().mkString("\n")
      formatter.reads(Json.parse(jsonString)) match {
        case json: JsSuccess[B] => json.get
        case JsError(errors) =>
          throw new S3ObjectJsonParsinException(s"[${s3obj.getBucketName()}(${s3obj.getKey()})] Error parsing [$jsonString]: ${errors mkString ","}")
      }
    } finally {
      is.close
    }
  }

}

trait S3BlobStore[A, B] extends S3ObjectStore[A, B] {

  protected def encodeValue(value: B): Array[Byte]

  protected def decodeValue(data: Array[Byte]): B

  protected def packValue(value: B) = {
    val content = encodeValue(value)
    val metadata = new ObjectMetadata()
    metadata.setContentType("application/octet-stream")
    metadata.setContentLength(content.length)
    val inputStream = new ByteArrayInputStream(content)
    (inputStream, metadata)
  }

  protected def unpackValue(s3Obj: S3Object): B = {
    val dataStream = s3Obj.getObjectContent
    try {
      decodeValue(IOUtils.toByteArray(dataStream))
    } finally {
      dataStream.close()
    }
  }

}

trait BlobFormat[B] {
  val format: BinaryFormat[B]
  protected def encodeValue(value: B): Array[Byte] = format.writes(Some(value))
  protected def decodeValue(data: Array[Byte]): B = format.reads(data).get
}

trait S3InboxDirectory {
  val dir: File
}

trait S3FileStore[A] extends S3ObjectStore[A, File] { self =>

  protected val inbox: S3InboxDirectory

  protected def packValue(value: File) = {
    require(value.isFile, s"$value is not a file.")
    val metadata = new ObjectMetadata()
    metadata.addUserMetadata("name", value.getName)
    metadata.setContentLength(value.length())
    (FileUtils.openInputStream(value), metadata)
  }

  protected def unpackValue(s3obj: S3Object) = {
    val metadata = s3obj.getObjectMetadata
    val name = metadata.getUserMetadata.get("name")
    val file = new File(inbox.dir, name)
    file.deleteOnExit()
    val contentStream = s3obj.getObjectContent
    try { FileUtils.copyInputStreamToFile(contentStream, file) }
    finally { contentStream.close() }
    file
  }
}
