package com.keepit.model

import com.keepit.commanders.{ CropImageRequest, ProcessImageRequest, ScaleImageRequest }
import com.keepit.common.db.{ Id, Model, State, States }
import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.common.time._
import org.joda.time.DateTime

case class KeepImage(
    id: Option[Id[KeepImage]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[KeepImage] = KeepImageStates.ACTIVE,
    keepId: Id[Keep],
    imagePath: ImagePath,
    format: ImageFormat,
    width: Int,
    height: Int,
    source: ImageSource,
    sourceFileHash: ImageHash,
    sourceImageUrl: Option[String],
    isOriginal: Boolean,
    kind: ProcessImageOperation) extends BaseImage with Model[KeepImage] {

  def dimensions = ImageSize(width, height)
  def withId(id: Id[KeepImage]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object KeepImage {
  val label = "keep"
  import com.keepit.model.ProcessImageOperation._
  def toProcessImageRequest(keepImage: KeepImage): ProcessImageRequest = keepImage.kind match {
    case Crop => CropImageRequest(keepImage.dimensions)
    case ki => ScaleImageRequest(keepImage.dimensions)
  }
}

object KeepImageStates extends States[KeepImage]
