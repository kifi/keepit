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

case class LibraryImagePosition(x: Option[Int], y: Option[Int])

object LibraryImageStates extends States[LibraryImage]
