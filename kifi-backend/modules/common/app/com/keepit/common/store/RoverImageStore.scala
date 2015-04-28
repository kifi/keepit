package com.keepit.common.store

import java.io.{ File, FileOutputStream, InputStream }

import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.transfer.TransferManager
import com.keepit.common.core._
import com.keepit.model.{ ImageFormat, ImageHash, ProcessImageOperation }
import org.apache.commons.io.{ FileUtils, IOUtils }
import play.api.Play
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.Play.current

import scala.collection.mutable.HashMap
import scala.concurrent.{ ExecutionContext, Future }

case class ImagePath(path: String)

object ImagePath {
  def apply(prefix: String, hash: ImageHash, size: ImageSize, kind: ProcessImageOperation, format: ImageFormat): ImagePath = {
    val fileName = hash.hash + "_" + size.width + "x" + size.height + kind.fileNameSuffix + "." + format.value
    ImagePath(prefix + "/" + fileName)
  }

  implicit val format: Format[ImagePath] = __.format[String].inmap(ImagePath(_), _.path)
}

trait RoverImageStore {
  def put(key: ImagePath, is: InputStream, contentLength: Int, mimeType: String): Future[Unit]
  def get(key: ImagePath): Future[File]
}

case class RoverImageStoreInbox(dir: File) extends S3InboxDirectory

class S3RoverImageStoreImpl(
    s3ImageConfig: S3ImageConfig,
    val inbox: RoverImageStoreInbox,
    implicit val transferManager: TransferManager,
    implicit val executionContext: ExecutionContext) extends RoverImageStore with S3AsyncHelper {

  def put(key: ImagePath, is: InputStream, contentLength: Int, mimeType: String): Future[Unit] = {
    val om = new ObjectMetadata()
    om.setContentType(mimeType)
    if (contentLength > 0) {
      om.setCacheControl("public, max-age=31556926") // standard way to cache "forever" (ie, one year)
      om.setContentLength(contentLength)
      asyncUpload(s3ImageConfig.bucketName, key.path, is, om).imap { case _ => () }
    } else {
      Future.failed {
        new RuntimeException(s"Invalid contentLength for $key: $contentLength")
      }
    }
  } andThen { case _ => is.close() }

  def get(key: ImagePath): Future[File] = {
    asyncDownload(s3ImageConfig.bucketName, key.path).imap(_.file)
  }
}

class InMemoryRoverImageStoreImpl extends RoverImageStore {

  require(!(Play.maybeApplication.isDefined && Play.isProd), "Can't have in memory file store in production")

  private val pathMap = new HashMap[ImagePath, String]()
  private val localStore = FileUtils.getTempDirectory

  def put(key: ImagePath, is: InputStream, contentLength: Int, mimeType: String): Future[Unit] = {
    try {
      val copy = new File(localStore, key.path)
      copy.deleteOnExit()
      val copyStream = new FileOutputStream(copy)
      IOUtils.copy(is, copyStream)
      copyStream.close()
      pathMap += (key -> copy.getAbsolutePath)
      Future.successful(())
    } catch {
      case error: Exception => Future.failed(error)
    } finally {
      is.close()
    }
  }

  def get(key: ImagePath): Future[File] = {
    try { Future.successful(pathMap.get(key).map(new File(_)).get) }
    catch { case error: Exception => Future.failed(error) }
  }
}
