package com.keepit.common.store

import java.io.InputStream

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.services.s3.transfer.model.UploadResult
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@ImplementedBy(classOf[LibraryImageStoreImpl])
trait LibraryImageStore {
  def put(key: String, is: InputStream, contentLength: Int, mimeType: String): Future[UploadResult]
  def get(key: String): Future[TemporaryFile]
}

@Singleton
class LibraryImageStoreImpl @Inject() (
    s3ImageConfig: S3ImageConfig,
    implicit val transferManager: TransferManager) extends LibraryImageStore with S3AsyncHelper {

  def put(key: String, is: InputStream, contentLength: Int, mimeType: String): Future[UploadResult] = {
    val om = new ObjectMetadata()
    om.setContentType(mimeType)
    if (contentLength > 0) {
      om.setCacheControl("public, max-age=31556926") // standard way to cache "forever" (ie, one year)
      om.setContentLength(contentLength)
      asyncUpload(s3ImageConfig.bucketName, key, is, om).map { res =>
        is.close()
        res
      }
    } else {
      Future.failed {
        is.close()
        new RuntimeException(s"Invalid contentLength for $key: $contentLength")
      }
    }
  }

  def get(key: String): Future[TemporaryFile] = {
    asyncDownload(s3ImageConfig.bucketName, key)
  }
}

