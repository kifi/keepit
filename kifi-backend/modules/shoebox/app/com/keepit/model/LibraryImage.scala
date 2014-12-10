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
    centerX: Int,         // percent value
    centerY: Int,         // percent value
    selectedWidth: Int,   // percent value
    selectedHeight: Int,  // percent value
    imagePath: String,
    format: ImageFormat,
    source: BaseImageSource,
    sourceFileHash: ImageHash,
    sourceImageUrl: Option[String],
    isOriginal: Boolean) extends BaseImage with Model[LibraryImage] {

  val imageSelection = LibraryImageSelection(centerX, centerY, selectedWidth, selectedHeight)
  def withId(id: Id[LibraryImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

case class LibraryImageSelection(
  centerX: Int, centerY: Int,
  selectedWidth: Int, selectedHeight: Int)

object LibraryImageStates extends States[LibraryImage]
