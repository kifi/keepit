package com.keepit.model

import com.keepit.common.db.{ Id, Model, State, States }
import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationLogo(
    id: Option[Id[OrganizationLogo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationLogo] = OrganizationImageStates.ACTIVE,
    organizationId: Id[Organization],
    position: Option[ImagePosition],
    width: Int,
    height: Int,
    format: ImageFormat,
    kind: ProcessImageOperation,
    imagePath: ImagePath,
    source: ImageSource,
    sourceFileHash: ImageHash,
    sourceImageURL: Option[String]) extends BaseImage with Model[OrganizationLogo] {
  def isOriginal = true

  def dimensions = ImageSize(width, height)
  def withId(id: Id[OrganizationLogo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object OrganizationLogo {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationLogo]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[OrganizationLogo]) and
    (__ \ 'organizationId).format(Id.format[Organization]) and
    (__ \ "position").formatNullable[ImagePosition] and
    (__ \ 'width).format[Int] and
    (__ \ 'height).format[Int] and
    (__ \ 'format).format[ImageFormat] and
    (__ \ 'kind).format[ProcessImageOperation] and
    (__ \ 'imagePath).format[ImagePath] and
    (__ \ 'source).format[ImageSource] and
    (__ \ 'sourceFileHash).format[ImageHash] and
    (__ \ 'sourceImageURL).formatNullable[String]
  )(OrganizationLogo.apply, unlift(OrganizationLogo.unapply))
}

case class ImagePosition(x: Int, y: Int)

object ImagePosition {
  implicit val format: Format[ImagePosition] = (
    (__ \ "x").format[Int] and
    (__ \ "y").format[Int]
  )(ImagePosition.apply, unlift(ImagePosition.unapply))
}

object OrganizationImageStates extends States[OrganizationLogo]
