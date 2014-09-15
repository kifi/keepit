package com.keepit.model

import com.keepit.common.db.{ States, State, Model, Id }
import com.keepit.common.store.ImageSize
import com.keepit.common.time._
import org.joda.time.DateTime

case class KeepImage(
    id: Option[Id[KeepImage]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[KeepImage] = KeepImageStates.ACTIVE,
    keepId: Id[Keep],
    imageUrl: String,
    format: ImageFormat,
    width: Int,
    height: Int,
    source: KeepImageSource,
    sourceFileHash: ImageHash,
    sourceImageUrl: Option[String],
    isOriginal: Boolean) extends Model[KeepImage] {
  val imageSize = ImageSize(width, height)
  def withId(id: Id[KeepImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object KeepImageStates extends States[KeepImage]

sealed abstract class KeepImageSource(val name: String)
object KeepImageSource {
  case object Embedly extends KeepImageSource("embedly")
  case object Unknown extends KeepImageSource("unknown")
  case object PagePeeker extends KeepImageSource("pagepeeker")
  case object UserPicked extends KeepImageSource("user_picked")
  case object UserUpload extends KeepImageSource("user_upload")

  private val all: Seq[KeepImageSource] = Seq(Embedly, Unknown, PagePeeker, UserUpload)
  def apply(name: String) = {
    all.find(_.name == name).getOrElse(throw new Exception(s"Can't find KeepImageSource for $name"))
  }

}
case class ImageHash(hash: String)
