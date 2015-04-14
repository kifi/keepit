package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.store.ImageSize
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ImageInfo(
    id: Option[Id[ImageInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ImageInfo] = ImageInfoStates.ACTIVE,
    seq: SequenceNumber[ImageInfo] = SequenceNumber.ZERO,
    uriId: Id[NormalizedURI],
    url: Option[String],
    name: String = RandomStringUtils.randomAlphanumeric(5),
    caption: Option[String] = None,
    width: Option[Int] = None,
    height: Option[Int] = None,
    size: Option[Int] = None,
    provider: Option[ImageProvider] = None,
    format: Option[ImageFormat] = None,
    priority: Option[Int] = None,
    path: String // the s3 bucket key name
    ) extends ModelWithState[ImageInfo] with ModelWithSeqNumber[ImageInfo] {
  val defaultImageFormat = ImageFormat.JPG
  def withId(imageInfoId: Id[ImageInfo]) = copy(id = Some(imageInfoId))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
  def getImageSize: Option[ImageSize] = for {
    w <- width
    h <- height
  } yield ImageSize(w, h)
  def getFormatSuffix: String = format match {
    case Some(f) => f.value
    case None => defaultImageFormat.value
  }
}

object ImageInfo {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ImageInfo]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[ImageInfo]) and
    (__ \ 'seq).format(SequenceNumber.format[ImageInfo]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'name).format[String] and
    (__ \ 'caption).formatNullable[String] and
    (__ \ 'width).formatNullable[Int] and
    (__ \ 'height).formatNullable[Int] and
    (__ \ 'size).formatNullable[Int] and
    (__ \ 'provider).formatNullable[ImageProvider] and
    (__ \ 'format).formatNullable[ImageFormat] and
    (__ \ 'priority).formatNullable[Int] and
    (__ \ 'path).format[String]
  )(ImageInfo.apply _, unlift(ImageInfo.unapply))
}

