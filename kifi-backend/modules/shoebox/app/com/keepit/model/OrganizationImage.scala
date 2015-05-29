package com.keepit.model

import com.keepit.common.db.{States, Model, State, Id}
import com.keepit.common.store.{ImageSize, ImagePath}
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationImage(
                         id: Option[Id[OrganizationImage]] = None,
                         createdAt: DateTime = currentDateTime,
                         updatedAt: DateTime = currentDateTime,
                         state: State[OrganizationImage] = OrganizationImageStates.ACTIVE,
                         libraryId: Id[Organization],
                         width: Int,
                         height: Int,
                         positionX: Option[Int], // rounded percent value
                         positionY: Option[Int], // rounded percent value
                         imagePath: ImagePath,
                         format: ImageFormat,
                         source: ImageSource,
                         sourceFileHash: ImageHash,
                         isOriginal: Boolean) extends BaseImage with Model[OrganizationImage] {

  def dimensions = ImageSize(width, height)
  def position = OrganizationImagePosition(positionX, positionY)
  def withId(id: Id[OrganizationImage]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object OrganizationImage {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationImage]) and
      (__ \ 'createdAt).format(DateTimeJsonFormat) and
      (__ \ 'updatedAt).format(DateTimeJsonFormat) and
      (__ \ 'state).format(State.format[OrganizationImage]) and
      (__ \ 'libraryId).format(Id.format[Organization]) and
      (__ \ 'width).format[Int] and
      (__ \ 'height).format[Int] and
      (__ \ 'positionX).formatNullable[Int] and
      (__ \ 'positionY).formatNullable[Int] and
      (__ \ 'imagePath).format[ImagePath] and
      (__ \ 'format).format[ImageFormat] and
      (__ \ 'source).format[ImageSource] and
      (__ \ 'sourceFileHash).format[ImageHash] and
      (__ \ 'isOriginal).format[Boolean]
    )(OrganizationImage.apply, unlift(OrganizationImage.unapply))
}

case class OrganizationImagePosition(x: Option[Int], y: Option[Int])

object OrganizationImageStates extends States[OrganizationImage]
