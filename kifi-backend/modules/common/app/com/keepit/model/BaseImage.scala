package com.keepit.model

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

import com.keepit.common.store.ImageSize
import org.joda.time.DateTime
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.{ JsString, JsValue, Json, Format }

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
  case object TwitterSync extends UserInitiated("twitter_sync")
  case object Unknown extends ImageSource("unknown")

  private val all: Seq[ImageSource] = Seq(Unknown, Embedly, PagePeeker, EmbedlyOrPagePeeker, UserUpload, UserPicked, TwitterSync)
  def apply(name: String) = {
    all.find(_.name == name).getOrElse(throw new Exception(s"Can't find ImageSource for $name"))
  }

  implicit val format = new Format[ImageSource] {
    def writes(imageSource: ImageSource) = JsString(imageSource.name)
    def reads(json: JsValue) = json.validate[String].map(j => ImageSource(j))
  }
}

sealed trait ImageProcessState
sealed trait ImageStoreInProgress extends ImageProcessState
sealed trait ImageProcessDone extends ImageProcessState

sealed trait ImageProcessSuccess extends ImageProcessDone
sealed abstract class ImageStoreFailure(val reason: String) extends ImageProcessState with ImageProcessDone
sealed abstract class ImageStoreFailureWithException(ex: Throwable, reason: String) extends ImageStoreFailure(reason) {
  def getCause: Throwable = ex
}

object ImageProcessState {
  // In-progress
  case class ImageLoadedAndHashed(file: TemporaryFile, format: ImageFormat, hash: ImageHash, sourceImageUrl: Option[String]) extends ImageStoreInProgress
  case class ImageValid(image: BufferedImage, format: ImageFormat, hash: ImageHash, processOperation: ProcessImageOperation) extends ImageStoreInProgress
  case class ReadyToPersist(key: String, format: ImageFormat, is: ByteArrayInputStream, image: BufferedImage, bytes: Int, processOperation: ProcessImageOperation) extends ImageStoreInProgress
  case class UploadedImage(key: String, format: ImageFormat, image: BufferedImage, processOperation: ProcessImageOperation) extends ImageStoreInProgress

  // Failures
  case class UpstreamProviderFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "upstream_provider_failed")
  case object UpstreamProviderNoImage extends ImageStoreFailure("upstream_provider_no_image")
  case class SourceFetchFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "source_fetch_failed")
  case class HashFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "image_hash_failed")
  case class InvalidImage(ex: Throwable) extends ImageStoreFailureWithException(ex, "invalid_image")
  case class DbPersistFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "db_persist_failed")
  case class CDNUploadFailed(ex: Throwable) extends ImageStoreFailureWithException(ex, "cdn_upload_failed")

  // Success
  case class StoreSuccess(format: ImageFormat, size: ImageSize, bytes: Int) extends ImageProcessState with ImageProcessSuccess
  case class ExistingStoredImagesFound(images: Seq[BaseImage]) extends ImageProcessState with ImageProcessSuccess
}

case class ImageHash(hash: String)

object ImageHash {
  implicit val format = Json.format[ImageHash]
}

sealed abstract class ProcessImageOperation(val kind: String, val fileNameSuffix: String)
object ProcessImageOperation {
  object Original extends ProcessImageOperation("original", "_o")
  case object Scale extends ProcessImageOperation("scale", "_s")
  case object Crop extends ProcessImageOperation("crop", "_c")

  val all = Scale :: Crop :: Original :: Nil

  def apply(kind: String): ProcessImageOperation = {
    all.find(_.kind == kind) getOrElse Original
  }

}
