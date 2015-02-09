package com.keepit.common.store

import java.io.InputStream
import com.amazonaws.event.{ ProgressEvent, ProgressListener }
import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.services.s3.transfer.model.UploadResult
import play.api.libs.Files.TemporaryFile

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.AmazonS3

trait S3AsyncHelper {

  def s3Client: AmazonS3

  def asyncUpload(bucketName: String, key: String, is: InputStream, objectMetadata: ObjectMetadata)(implicit tm: TransferManager): Future[UploadResult] = {
    val p = Promise[UploadResult]()
    val upload = tm.upload(bucketName, key, is, objectMetadata)
    upload.addProgressListener(new ProgressListener {
      override def progressChanged(p1: ProgressEvent): Unit = {
        if (!p.isCompleted) {
          p1.getEventCode match {
            case ProgressEvent.COMPLETED_EVENT_CODE =>
              Try(upload.waitForUploadResult()) match {
                case Success(succ) => p.complete(Success(succ))
                case Failure(ex) => p.failure(ex)
              }
            case ProgressEvent.CANCELED_EVENT_CODE =>
              p.failure(new RuntimeException(s"Transfer cancelled: $bucketName / $key"))
            case ProgressEvent.FAILED_EVENT_CODE =>
              p.failure(new RuntimeException(s"Transfer failed: $bucketName / $key"))
            case _ =>
          }
        }
      }
    })
    p.future
  }

  def asyncDownload(bucketName: String, key: String)(implicit tm: TransferManager): Future[TemporaryFile] = {
    val p = Promise[TemporaryFile]()
    val tf = TemporaryFile(prefix = bucketName, suffix = key)
    tf.file.deleteOnExit()
    val transfer = Try {
      val download = tm.download(bucketName, key, tf.file)
      download.addProgressListener(new ProgressListener {
        override def progressChanged(p1: ProgressEvent): Unit = {
          if (!p.isCompleted) {
            p1.getEventCode match {
              case ProgressEvent.COMPLETED_EVENT_CODE =>
                p.complete(Success(tf))
              case ProgressEvent.CANCELED_EVENT_CODE =>
                p.failure(new RuntimeException(s"Transfer cancelled: $bucketName / $key"))
              case ProgressEvent.FAILED_EVENT_CODE =>
                p.failure(new RuntimeException(s"Transfer failed: $bucketName / $key"))
              case _ =>
            }
          }
        }
      })
    }
    transfer match {
      case Success(_) => p.future
      case Failure(ex) =>
        p.failure(ex).future
    }
  }

}
