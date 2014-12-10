package com.keepit.model

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

import com.keepit.common.store.ImageSize
import org.joda.time.DateTime
import play.api.libs.Files.TemporaryFile

abstract class BaseImage {
  val createdAt: DateTime
  val updatedAt: DateTime
  val imagePath: String
  val format: ImageFormat
  val width: Int
  val height: Int
  val source: ImageSource
  val sourceFileHash: ImageHash
  val isOriginal: Boolean
  val imageSize = ImageSize(width, height)
}

abstract class ImageSource(val name: String)
object ImageSource {
  sealed abstract class UserInitiated(name: String) extends ImageSource(name)
  sealed abstract class SystemInitiated(name: String) extends ImageSource(name)

  case object Embedly extends SystemInitiated("embedly")
  case object PagePeeker extends SystemInitiated("pagepeeker")
  case object EmbedlyOrPagePeeker extends SystemInitiated("embedly_or_pagepeeker")
  case object UserPicked extends UserInitiated("user_picked")
  case object UserUpload extends UserInitiated("user_upload")
  case object Unknown extends ImageSource("unknown")

  private val all: Seq[ImageSource] = Seq(Unknown, Embedly, PagePeeker, EmbedlyOrPagePeeker, UserUpload, UserPicked)
  def apply(name: String) = {
    all.find(_.name == name).getOrElse(throw new Exception(s"Can't find ImageSource for $name"))
  }
}

sealed trait ImageProcessState
sealed trait ImageProcessDone extends ImageProcessState
sealed trait ImageProcessSuccess extends ImageProcessDone
sealed trait ImageStoreInProgress extends ImageProcessState
sealed abstract class ImageStoreFailure(val reason: String) extends ImageProcessState with ImageProcessDone
sealed abstract class ImageStoreFailureWithException(ex: Throwable, reason: String) extends ImageStoreFailure(reason)
object ImageProcessState {
  // In-progress
  case class ImageLoadedAndHashed(file: TemporaryFile, format: ImageFormat, hash: ImageHash, sourceImageUrl: Option[String]) extends ImageStoreInProgress
  case class ImageValid(image: BufferedImage, format: ImageFormat, hash: ImageHash) extends ImageStoreInProgress
  case class ReadyToPersist(key: String, format: ImageFormat, is: ByteArrayInputStream, image: BufferedImage, bytes: Int) extends ImageStoreInProgress
  case class UploadedImage(key: String, format: ImageFormat, image: BufferedImage) extends ImageStoreInProgress

  // Failures
  case class UpstreamProviderFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "upstream_provider_failed")
  case object UpstreamProviderNoImage$ extends ImageStoreFailure("upstream_provider_no_image")
  case class SourceFetchFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "source_fetch_failed")
  case class HashFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "image_hash_failed")
  case class InvalidImage(ex: Throwable) extends ImageStoreFailureWithException(ex, "invalid_image")
  case class DbPersistFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "db_persist_failed")
  case class CDNUploadFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "cdn_upload_failed")

  // Success
  case object StoreSuccess extends ImageProcessState with ImageProcessSuccess
  case class ExistingStoredImagesFound(images: Seq[BaseImage]) extends ImageProcessState with ImageProcessSuccess
}

case class ImageHash(hash: String)

