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
    width: Int,
    height: Int,
    cropCenterX: Option[Int], // rounded percent value
    cropCenterY: Option[Int], // rounded percent value
    cropWidth: Option[Int], // rounded percent value
    cropHeight: Option[Int], // rounded percent value
    imagePath: String,
    format: ImageFormat,
    source: ImageSource,
    sourceFileHash: ImageHash,
    isOriginal: Boolean) extends BaseImage with Model[LibraryImage] {

  val imagePosition = LibraryImagePosition.getPositions(cropCenterX, cropCenterY, cropWidth, cropHeight)
  def withId(id: Id[LibraryImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

case class LibraryImagePosition(
  centerX: Int, centerY: Int,
  width: Int, height: Int)

object LibraryImagePosition {
  val default = LibraryImagePosition(50, 50, 100, 100)

  def getPositions(centerX: Option[Int], centerY: Option[Int], width: Option[Int], height: Option[Int]): Option[LibraryImagePosition] = {
    (centerX, centerY, width, height) match {
      case (Some(cx), Some(cy), Some(w), Some(h)) =>
        Some(LibraryImagePosition(cx, cy, w, h))
      case _ =>
        None
    }
  }
}

object LibraryImageStates extends States[LibraryImage]
