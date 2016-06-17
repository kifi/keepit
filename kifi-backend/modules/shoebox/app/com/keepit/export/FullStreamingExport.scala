package com.keepit.export

import java.io.{ File, FileInputStream }

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ ObjectMetadata, PutObjectResult }
import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.services.s3.transfer.model.UploadResult
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ S3AsyncHelper, S3InboxDirectory, S3Bucket, S3Helper }
import com.keepit.discussion.{ CrossServiceDiscussion, Message }
import com.keepit.model._
import com.keepit.rover.model.RoverUriSummary
import com.keepit.social.BasicUser
import play.api.libs.Files.TemporaryFile
import play.api.libs.iteratee.Enumerator

import scala.concurrent.Future
import scala.util.Try

object FullStreamingExport {
  final case class Root(user: BasicUser, spaces: Enumerator[SpaceExport])
  final case class SpaceExport(space: Either[BasicUser, BasicOrganization], libraries: Enumerator[LibraryExport])
  final case class LibraryExport(library: Library, keeps: Enumerator[KeepExport])
  final case class KeepExport(keep: Keep, discussion: Option[CrossServiceDiscussion], uri: Option[RoverUriSummary])
}

case class KifiExportConfig(bucket: S3Bucket)
case class KifiExportInbox(dir: File) extends S3InboxDirectory

class S3KifiExportStore @Inject() (
  config: KifiExportConfig,
  val s3Client: AmazonS3,
  val inbox: KifiExportInbox,
  implicit val tm: TransferManager)
    extends S3AsyncHelper with Logging {
  def store(file: File): Future[UploadResult] = {
    asyncUpload(config.bucket.name, file.getName, new FileInputStream(file), new ObjectMetadata())
  }
  def retrieve(filename: String): Future[TemporaryFile] = {
    asyncDownload(config.bucket.name, filename)
  }
}

