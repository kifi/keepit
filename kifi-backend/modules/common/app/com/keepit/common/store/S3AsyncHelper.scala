package com.keepit.common.store

import java.io.InputStream
import com.amazonaws.event.{ ProgressEvent, ProgressListener, ProgressEventType }
import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.services.s3.transfer.model.UploadResult
import com.keepit.common.concurrent.ExecutionContext
import org.apache.commons.io.FileUtils
import play.api.libs.Files.TemporaryFile

import scala.concurrent.{ Future, Promise }
import scala.util.{ Success, Try }
import com.amazonaws.services.s3.model.{ ObjectMetadata }
import com.keepit.common.core._

trait S3AsyncHelper {

  protected val inbox: S3InboxDirectory

  def asyncUpload(bucketName: String, key: String, is: InputStream, objectMetadata: ObjectMetadata)(implicit tm: TransferManager): Future[UploadResult] = {
    safelyComplete[UploadResult] { p =>
      val upload = tm.upload(bucketName, key, is, objectMetadata)
      upload.addProgressListener(completeWith(p, Try(upload.waitForUploadResult()), s"$bucketName / $key"))
    }
  }

  def asyncDownload(bucketName: String, key: String)(implicit tm: TransferManager): Future[TemporaryFile] = {
    safelyComplete[TemporaryFile] { p =>
      val file = {
        val name = s"$bucketName/$key.tmp"
        new File(inbox.dir, name)
      }
      file.deleteOnExit()
      val download = tm.download(bucketName, key, file)
      download.addProgressListener(completeWith(p, Success(new TemporaryFile(file)), s"$bucketName / $key"))
    }
  }

  def get[T](bucketName: String, key: String, unpack: InputStream => T)(implicit tm: TransferManager): Future[T] = {
    asyncDownload(bucketName, key).imap { temp =>
      val inputStream = FileUtils.openInputStream(temp.file)
      try { unpack(inputStream) }
      finally { inputStream.close() }
    }
  }

  def put[T](bucketName: String, key: String, value: T, pack: T => InputStream, metadata: ObjectMetadata)(implicit tm: TransferManager): Future[Unit] = {
    safelyComplete[UploadResult] { p =>
      val inputStream = pack(value)
      p.future.onComplete { _ => inputStream.close() }(ExecutionContext.immediate)
      val upload = tm.upload(bucketName, key, inputStream, metadata)
      upload.addProgressListener(completeWith(p, Try({ upload.waitForUploadResult() }), s"$bucketName / $key"))
    } imap (_ => ())
  }

  private def safelyComplete[T](complete: Promise[T] => Unit): Future[T] = {
    val promise = Promise[T]()
    Try(complete(promise)) recover { case error => promise.failure(error) }
    promise.future
  }

  private def completeWith[T](promise: Promise[T], getResult: => Try[T], name: String): ProgressListener = new ProgressListener {
    override def progressChanged(progressEvent: ProgressEvent): Unit = {
      if (!promise.isCompleted) {
        progressEvent.getEventType match {
          case ProgressEventType.TRANSFER_COMPLETED_EVENT =>
            promise.complete(getResult)
          case ProgressEventType.TRANSFER_CANCELED_EVENT =>
            promise.failure(new RuntimeException(s"Transfer cancelled: $name"))
          case ProgressEventType.TRANSFER_PART_FAILED_EVENT =>
            promise.failure(new RuntimeException(s"Transfer failed: $name"))
          case _ =>
        }
      }
    }
  }

}
