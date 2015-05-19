package com.keepit.model

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.rover.article.{ ArticleKind, Article }
import org.joda.time.DateTime
import play.api.libs.Files.TemporaryFile
import play.api.libs.json._

abstract class BaseImage {
  val createdAt: DateTime
  val updatedAt: DateTime
  val format: ImageFormat
  val width: Int
  val height: Int
  val source: ImageSource
  def sourceFileHash: ImageHash
  def imagePath: ImagePath
  def isOriginal: Boolean
  val imageSize = ImageSize(width, height)
}

case class ImageFormat(value: String)
object ImageFormat {
  val JPG = ImageFormat("jpg")
  val PNG = ImageFormat("png")
  val GIF = ImageFormat("gif")
  val UNKNOWN = ImageFormat("unknown")
  implicit val imageFormatFormat = new Format[ImageFormat] {
    def reads(json: JsValue): JsResult[ImageFormat] = {
      json.asOpt[String] match {
        case Some(str) => JsSuccess(ImageFormat(str))
        case None => JsError()
      }
    }
    def writes(kind: ImageFormat): JsValue = {
      JsString(kind.value)
    }
  }
}

abstract class ImageSource(val name: String)

object ImageSource {
  sealed abstract class UserInitiated(name: String) extends ImageSource(name)
  sealed abstract class SystemInitiated(name: String) extends ImageSource(name)

  case object EmbedlyOrPagePeeker extends SystemInitiated("embedly_or_pagepeeker")
  case object Embedly extends SystemInitiated("embedly")
  case object UserPicked extends UserInitiated("user_picked")
  case object UserUpload extends UserInitiated("user_upload")
  case object TwitterSync extends UserInitiated("twitter_sync")
  case object Unknown extends ImageSource("unknown")
  case class RoverArticle[A <: Article](kind: ArticleKind[A]) extends SystemInitiated(s"${kind.typeCode}_article")

  private val all: Seq[ImageSource] = Seq(Unknown, Embedly, EmbedlyOrPagePeeker, UserUpload, UserPicked, TwitterSync) ++ ArticleKind.all.map(RoverArticle(_))
  def apply(name: String) = all.find(_.name == name).getOrElse(throw new Exception(s"Can't find ImageSource for $name"))

  implicit val format = new Format[ImageSource] {
    def writes(imageSource: ImageSource) = JsString(imageSource.name)
    def reads(json: JsValue) = json.validate[String].map(j => ImageSource(j))
  }
}

sealed trait ImageProcessState
sealed trait ImageStoreInProgress extends ImageProcessState
sealed trait ImageProcessDone extends ImageProcessState

sealed trait ImageProcessSuccess extends ImageProcessDone
sealed abstract class ImageStoreFailure(val reason: String, val cause: Option[Throwable] = None) extends ImageProcessState with ImageProcessDone

object ImageProcessState {
  // In-progress
  case class ImageLoadedAndHashed(file: TemporaryFile, format: ImageFormat, hash: ImageHash, sourceImageUrl: Option[String]) extends ImageStoreInProgress
  case class ImageValid(image: BufferedImage, format: ImageFormat, hash: ImageHash, processOperation: ProcessImageOperation) extends ImageStoreInProgress
  case class ReadyToPersist(key: ImagePath, format: ImageFormat, is: ByteArrayInputStream, image: BufferedImage, bytes: Int, processOperation: ProcessImageOperation) extends ImageStoreInProgress
  case class UploadedImage(key: ImagePath, format: ImageFormat, image: BufferedImage, processOperation: ProcessImageOperation) extends ImageStoreInProgress

  // Failures
  case class UpstreamProviderFailed(ex: Throwable) extends ImageStoreFailure("upstream_provider_failed", Some(ex))
  case object UpstreamProviderNoImage extends ImageStoreFailure("upstream_provider_no_image")
  case class SourceFetchFailed(ex: Throwable) extends ImageStoreFailure("source_fetch_failed", Some(ex))
  case class HashFailed(ex: Throwable) extends ImageStoreFailure("image_hash_failed", Some(ex))
  case class InvalidImage(ex: Throwable) extends ImageStoreFailure("invalid_image", Some(ex))
  case class DbPersistFailed(ex: Throwable) extends ImageStoreFailure("db_persist_failed", Some(ex))
  case class CDNUploadFailed(ex: Throwable) extends ImageStoreFailure("cdn_upload_failed", Some(ex))

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
