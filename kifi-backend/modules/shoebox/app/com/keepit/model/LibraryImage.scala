package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.common.time._
import org.joda.time.DateTime
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class LibraryImage(
    id: Option[Id[LibraryImage]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryImage] = LibraryImageStates.ACTIVE,
    libraryId: Id[Library],
    width: Int,
    height: Int,
    positionX: Option[Int], // rounded percent value
    positionY: Option[Int], // rounded percent value
    imagePath: ImagePath,
    format: ImageFormat,
    source: ImageSource,
    sourceFileHash: ImageHash,
    isOriginal: Boolean) extends BaseImage with Model[LibraryImage] {

  def dimensions = ImageSize(width, height)
  def position = LibraryImagePosition(positionX, positionY)
  def withId(id: Id[LibraryImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object LibraryImage {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[LibraryImage]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryImage]) and
    (__ \ 'libraryId).format(Id.format[Library]) and
    (__ \ 'width).format[Int] and
    (__ \ 'height).format[Int] and
    (__ \ 'positionX).formatNullable[Int] and
    (__ \ 'positionY).formatNullable[Int] and
    (__ \ 'imagePath).format[ImagePath] and
    (__ \ 'format).format[ImageFormat] and
    (__ \ 'source).format[ImageSource] and
    (__ \ 'sourceFileHash).format[ImageHash] and
    (__ \ 'isOriginal).format[Boolean]
  )(LibraryImage.apply, unlift(LibraryImage.unapply))
}

@json case class LibraryImageInfo(path: ImagePath, x: Int, y: Int)

object LibraryImageInfo {
  def createInfo(img: LibraryImage) = LibraryImageInfo(img.imagePath, img.positionX.getOrElse(50), img.positionY.getOrElse(50))
}

case class LibraryImagePosition(x: Option[Int], y: Option[Int])

object LibraryImageStates extends States[LibraryImage]

case class LibraryImageKey(libraryId: Id[Library]) extends Key[Seq[LibraryImage]] {
  override val version = 1
  val namespace = "library_image"
  def toKey(): String = libraryId.toString
}

class LibraryImageCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryImageKey, Seq[LibraryImage]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

