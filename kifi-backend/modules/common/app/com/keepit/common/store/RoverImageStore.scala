package com.keepit.common.store

import java.io.{ File, InputStream }

import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.transfer.TransferManager
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.core._
import com.keepit.model.{ ImageFormat, ImageHash, ProcessImageOperation }
import play.api.Play
import play.api.libs.Files.TemporaryFile
import play.api.libs.json._
import play.api.Play.current

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

case class ImagePath(path: String) extends AnyVal {
  override def toString() = path
  def getUrl(implicit imageConfig: S3ImageConfig): String = imageConfig.cdnBase + "/" + path
}

object ImagePath {
  def apply(prefix: String, hash: ImageHash, size: ImageSize, kind: ProcessImageOperation, format: ImageFormat): ImagePath = {
    val fileName = hash.hash + "_" + size.width + "x" + size.height + kind.fileNameSuffix + "." + format.value
    ImagePath(prefix + "/" + fileName)
  }

  implicit val format: Format[ImagePath] = Format(__.read[String].map(ImagePath(_)), Writes(path => JsString(path.path)))
}

trait RoverImageStore {
  def put(key: ImagePath, is: InputStream, contentLength: Int, mimeType: String): Future[Unit]
  def get(key: ImagePath): Future[TemporaryFile]
}

case class RoverImageStoreInbox(dir: File) extends S3InboxDirectory

@Singleton
class S3RoverImageStoreImpl @Inject() (
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

  def get(key: ImagePath): Future[TemporaryFile] = {
    asyncDownload(s3ImageConfig.bucketName, key.path)
  }
}

class InMemoryRoverImageStoreImpl() extends RoverImageStore {

  require(!(Play.maybeApplication.isDefined && Play.isProd), "Can't have in memory file store in production")

  val cache = mutable.HashMap[ImagePath, TemporaryFile]()

  def put(key: ImagePath, is: InputStream, contentLength: Int, mimeType: String): Future[Unit] = {
    val tf = TemporaryFile(prefix = "test-file", suffix = ".png")
    tf.file.deleteOnExit()
    // Intentionally does not write the data to the actual file. If this is needed, please add a mutable flag in this class.
    cache.put(key, tf)
    is.close()
    Future.successful(())
  }
  def get(key: ImagePath): Future[TemporaryFile] = {
    cache.get(key) match {
      case Some(f) => Future.successful(f)
      case None => Future.failed(new RuntimeException("Key not there!"))
    }
  }

  def all: Map[ImagePath, TemporaryFile] = cache.toMap

}
