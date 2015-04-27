package com.keepit.rover.store

import java.io.{ FileOutputStream, File, InputStream }
import org.apache.commons.io.{ IOUtils }

import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.transfer.TransferManager
import com.google.inject.{ Inject }
import com.keepit.common.store._
import com.keepit.model.{ ImageFormat, ProcessImageOperation, ImageHash }
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

case class ImageStoreKey(path: String)

object ImageStoreKey {
  def apply(prefix: String, hash: ImageHash, size: ImageSize, kind: ProcessImageOperation, format: ImageFormat): ImageStoreKey = {
    val fileName = hash.hash + "_" + size.width + "x" + size.height + kind.fileNameSuffix + "." + format.value
    ImageStoreKey(prefix + "/" + fileName)
  }
}

trait RoverImageStore {
  def put(key: ImageStoreKey, is: InputStream, contentLength: Int, mimeType: String): Future[Unit]
  def get(key: ImageStoreKey): Future[File]
}

case class RoverImageStoreInbox(dir: File) extends S3InboxDirectory

class S3RoverImageStoreImpl(
    s3ImageConfig: S3ImageConfig,
    val inbox: RoverImageStoreInbox,
    implicit val transferManager: TransferManager,
    implicit val executionContext: ExecutionContext) extends RoverImageStore with S3AsyncHelper {

  def put(key: ImageStoreKey, is: InputStream, contentLength: Int, mimeType: String): Future[Unit] = {
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

  def get(key: ImageStoreKey): Future[File] = {
    asyncDownload(s3ImageConfig.bucketName, key.path).imap(_.file)
  }
}

class InMemoryRoverImageStoreImpl extends InMemoryFileStore[ImageStoreKey] with RoverImageStore {
  def put(key: ImageStoreKey, is: InputStream, contentLength: Int, mimeType: String): Future[Unit] = {
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

  def get(key: ImageStoreKey): Future[File] = {
    try { Future.successful(syncGet(key).get) }
    catch { case error: Exception => Future.failed(error) }
  }
}