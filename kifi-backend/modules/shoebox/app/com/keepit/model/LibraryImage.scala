package com.keepit.model

import com.keepit.common.db.{ States, Model, State, Id }
import com.keepit.common.store.ImageSize
import com.keepit.common.time._
import org.joda.time.DateTime

case class LibraryImage(
    id: Option[Id[LibraryImage]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryImage] = LibraryImageStates.ACTIVE,
    libraryId: Id[Library],
    imagePath: String,
    format: ImageFormat,
    width: Int,
    height: Int,
    offsetWidth: Int,
    offsetHeight: Int,
    source: LibraryImageSource,
    sourceFileHash: ImageHash,
    sourceImageUrl: Option[String],
    isOriginal: Boolean) extends Model[LibraryImage] {
  val imageSize = ImageSize(width, height)
  def withId(id: Id[LibraryImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object LibraryImageStates extends States[LibraryImage]

sealed abstract class LibraryImageSource(val name: String)
object LibraryImageSource {
  sealed abstract class UserInitiated(name: String) extends LibraryImageSource(name)
  sealed abstract class SystemInitiated(name: String) extends LibraryImageSource(name)

  case object UserUpload extends UserInitiated("user_upload")
  case object Unknown extends LibraryImageSource("unknown")

  private val all: Seq[LibraryImageSource] = Seq(Unknown, UserUpload)

  def apply(name: String) = {
    all.find(_.name == name).getOrElse(throw new Exception(s"Can't find LibraryImageSource for $name"))
  }

}
