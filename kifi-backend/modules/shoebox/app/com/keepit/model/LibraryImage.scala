package com.keepit.model

import com.keepit.common.db.{ States, Model, State, Id }
import com.keepit.common.time._
import org.joda.time.DateTime
import com.kifi.macros.json

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
    imagePath: String,
    format: ImageFormat,
    source: ImageSource,
    sourceFileHash: ImageHash,
    isOriginal: Boolean) extends BaseImage with Model[LibraryImage] {

  val imagePosition = LibraryImagePosition(positionX, positionY)
  def withId(id: Id[LibraryImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

@json case class LibraryImageInfo(path: String, x: Option[Int], y: Option[Int])
object LibraryImageInfo {
  def createInfo(img: LibraryImage) = LibraryImageInfo(img.imagePath, img.positionX, img.positionY)
}

case class LibraryImagePosition(x: Option[Int], y: Option[Int])

object LibraryImageStates extends States[LibraryImage]
