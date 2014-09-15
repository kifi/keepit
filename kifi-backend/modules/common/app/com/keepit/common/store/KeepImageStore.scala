package com.keepit.common.store

import java.io.InputStream

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.services.s3.transfer.model.UploadResult
import com.google.inject.{ ImplementedBy, Inject }
import play.api.libs.Files.TemporaryFile

import scala.concurrent.Future

@ImplementedBy(classOf[KeepImageStoreImpl])
trait KeepImageStore {
  def put(key: String, is: InputStream, contentLength: Int, mimeType: String): Future[UploadResult]
  def get(key: String): Future[TemporaryFile]
}

class KeepImageStoreImpl @Inject() (
    s3ImageConfig: S3ImageConfig,
    val s3Client: AmazonS3,
    implicit val transferManager: TransferManager) extends KeepImageStore with S3AsyncHelper {

  def put(key: String, is: InputStream, contentLength: Int, mimeType: String): Future[UploadResult] = {
    val om = new ObjectMetadata()
    om.setContentType(mimeType)
    if (contentLength > 0) {
      om.setCacheControl("public, max-age=31556926")
      om.setContentLength(contentLength)
      asyncUpload(s3ImageConfig.bucketName, key, is, om)
    } else {
      Future.failed(new RuntimeException(s"Invalid contentLength for $key: $contentLength"))
    }
  }

  def get(key: String): Future[TemporaryFile] = {
    asyncDownload(s3ImageConfig.bucketName, key)
  }
}

