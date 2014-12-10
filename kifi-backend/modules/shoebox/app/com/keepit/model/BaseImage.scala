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
  val source: BaseImageSource
  val sourceFileHash: ImageHash
  val sourceImageUrl: Option[String]
  val isOriginal: Boolean
  val imageSize = ImageSize(width, height)
}

sealed abstract class BaseImageSource(val name: String)
object BaseImageSource {
  sealed abstract class UserInitiated(name: String) extends BaseImageSource(name)
  sealed abstract class SystemInitiated(name: String) extends BaseImageSource(name)

  case object Embedly extends SystemInitiated("embedly")
  case object PagePeeker extends SystemInitiated("pagepeeker")
  case object EmbedlyOrPagePeeker extends SystemInitiated("embedly_or_pagepeeker")
  case object UserPicked extends UserInitiated("user_picked")
  case object UserUpload extends UserInitiated("user_upload")
  case object Unknown extends BaseImageSource("unknown")

  private val all: Seq[BaseImageSource] = Seq(Unknown, Embedly, PagePeeker, EmbedlyOrPagePeeker, UserUpload, UserPicked)
  def apply(name: String) = {
    all.find(_.name == name).getOrElse(throw new Exception(s"Can't find ImageSource for $name"))
  }

}

sealed trait BaseImageProcessState
sealed trait BaseImageProcessDone extends BaseImageProcessState
sealed trait BaseImageProcessSuccess extends BaseImageProcessDone
sealed trait BaseImageStoreInProgress extends BaseImageProcessState
sealed abstract class BaseImageStoreFailure(val reason: String) extends BaseImageProcessState with BaseImageProcessDone
sealed abstract class BaseImageStoreFailureWithException(ex: Throwable, reason: String) extends BaseImageStoreFailure(reason)
object BaseImageProcessState {
  // In-progress
  case class ImageLoadedAndHashed(file: TemporaryFile, format: ImageFormat, hash: ImageHash, sourceImageUrl: Option[String]) extends BaseImageStoreInProgress
  case class ImageValid(image: BufferedImage, format: ImageFormat, hash: ImageHash) extends BaseImageStoreInProgress
  case class ReadyToPersist(key: String, format: ImageFormat, is: ByteArrayInputStream, image: BufferedImage, bytes: Int) extends BaseImageStoreInProgress
  case class UploadedImage(key: String, format: ImageFormat, image: BufferedImage) extends BaseImageStoreInProgress

  // Failures
  case class UpstreamProviderFailed(ex: Throwable) extends BaseImageStoreFailureWithException(ex, "upstream_provider_failed")
  case object UpstreamProviderNoImage$ extends BaseImageStoreFailure("upstream_provider_no_image")
  case class SourceFetchFailed(ex: Throwable) extends BaseImageStoreFailureWithException(ex, "source_fetch_failed")
  case class HashFailed(ex: Throwable) extends BaseImageStoreFailureWithException(ex, "image_hash_failed")
  case class InvalidImage(ex: Throwable) extends BaseImageStoreFailureWithException(ex, "invalid_image")
  case class DbPersistFailed(ex: Throwable) extends BaseImageStoreFailureWithException(ex, "db_persist_failed")
  case class CDNUploadFailed(ex: Throwable) extends BaseImageStoreFailureWithException(ex, "cdn_upload_failed")

  // Success
  case object StoreSuccess extends BaseImageProcessState with BaseImageProcessSuccess
  case class ExistingStoredImagesFound(images: Seq[BaseImage]) extends BaseImageProcessState with BaseImageProcessSuccess
}

case class ImageHash(hash: String)

