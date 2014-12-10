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
    offsetWidth: Int,
    offsetHeight: Int,
    selectedWidth: Int,
    selectedHeight: Int,
    imagePath: String,
    format: ImageFormat,
    source: BaseImageSource,
    sourceFileHash: ImageHash,
    sourceImageUrl: Option[String],
    isOriginal: Boolean) extends BaseImage with Model[LibraryImage] {
  val imageSelection = LibraryImageSelection(selectedWidth, selectedHeight, offsetWidth, offsetHeight)
  def withId(id: Id[LibraryImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

case class LibraryImageSelection(
  selectedWidth: Int, selectedHeight: Int,
  offsetWidth: Int, offsetHeight: Int)

object LibraryImageStates extends States[LibraryImage]
