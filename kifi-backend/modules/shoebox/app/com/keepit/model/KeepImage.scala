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
    imagePath: String,
    format: ImageFormat,
    width: Int,
    height: Int,
    source: BaseImageSource,
    sourceFileHash: ImageHash,
    sourceImageUrl: Option[String],
    isOriginal: Boolean) extends BaseImage with Model[KeepImage] {
  //val imageSize = ImageSize(width, height)
  def withId(id: Id[KeepImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object KeepImageStates extends States[KeepImage]

/*
sealed abstract class KeepImageSource(val name: String)
object KeepImageSource {
  sealed abstract class UserInitiated(name: String) extends KeepImageSource(name)
  sealed abstract class SystemInitiated(name: String) extends KeepImageSource(name)

  case object Embedly extends SystemInitiated("embedly")
  case object PagePeeker extends SystemInitiated("pagepeeker")
  case object EmbedlyOrPagePeeker extends SystemInitiated("embedly_or_pagepeeker")
  case object UserPicked extends UserInitiated("user_picked")
  case object UserUpload extends UserInitiated("user_upload")
  case object Unknown extends KeepImageSource("unknown")

  private val all: Seq[KeepImageSource] = Seq(Unknown, Embedly, PagePeeker, EmbedlyOrPagePeeker, UserUpload, UserPicked)
  def apply(name: String) = {
    all.find(_.name == name).getOrElse(throw new Exception(s"Can't find KeepImageSource for $name"))
  }

}
case class ImageHash(hash: String)
*/
