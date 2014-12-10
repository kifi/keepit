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
    cropCenterX: Int, // rounded percent value
    cropCenterY: Int, // rounded percent value
    cropWidth: Int, // rounded percent value
    cropHeight: Int, // rounded percent value
    imagePath: String,
    format: ImageFormat,
    source: ImageSource,
    sourceFileHash: ImageHash,
    isOriginal: Boolean) extends BaseImage with Model[LibraryImage] {

  val imageSelection = LibraryImagePosition(cropCenterX, cropCenterY, cropWidth, cropHeight)
  def withId(id: Id[LibraryImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

case class LibraryImagePosition(
  centerX: Int, centerY: Int,
  width: Int, height: Int)

object LibraryImagePosition {
  val default = LibraryImagePosition(50, 50, 100, 100)
}

object LibraryImageStates extends States[LibraryImage]
