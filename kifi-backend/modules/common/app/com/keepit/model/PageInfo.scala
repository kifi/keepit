package com.keepit.model

import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._

trait PageSafetyInfo {
  def safe:Option[Boolean]
}

trait PageMediaInfo {
  def images:Seq[ImageGenericInfo]
}

trait PageGenericInfo {
  def description:Option[String]
  // def content:Option[String]
  def faviconUrl:Option[String]
}

object PageInfoStates extends States[PageInfo]

case class PageInfo(
  id:Option[Id[PageInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state:     State[PageInfo] = PageInfoStates.ACTIVE,
  seq:       SequenceNumber[PageInfo] = SequenceNumber.ZERO,
  uriId:Id[NormalizedURI],
  description:Option[String]      = None,
  safe:Option[Boolean]            = None,
  faviconUrl:Option[String]       = None,
  imageAvail:Option[Boolean]      = None,  // todo: removeme
  screenshotAvail:Option[Boolean] = None,  // todo: removeme
  imageInfoId:Option[Id[ImageInfo]] = None
) extends ModelWithState[PageInfo] with ModelWithSeqNumber[PageInfo] with PageGenericInfo with PageSafetyInfo {
  def withId(pageInfoId: Id[PageInfo]) = copy(id = Some(pageInfoId))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
  def withImageInfoId(imgInfoId: Id[ImageInfo]) = copy(imageInfoId = Some(imgInfoId))
}

object PageInfo {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[PageInfo]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[PageInfo]) and
    (__ \ 'seq).format(SequenceNumber.format[PageInfo]) and
    (__ \ 'uri_id).format(Id.format[NormalizedURI]) and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'favicon_url).formatNullable[String] and
    (__ \ 'imageAvail).formatNullable[Boolean] and
    (__ \ 'screenshotAvail).formatNullable[Boolean] and
    (__ \ 'imageInfoId).formatNullable(Id.format[ImageInfo])
  )(PageInfo.apply _, unlift(PageInfo.unapply))
}

object ImageInfoStates extends States[ImageInfo]

trait ImageGenericInfo {
  def url:String
  def caption:Option[String]
  def width:Option[Int]
  def height:Option[Int]
  def size:Option[Int]
}

case class ImageInfo(
  id:Option[Id[ImageInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state:     State[ImageInfo] = ImageInfoStates.ACTIVE,
  seq:       SequenceNumber[ImageInfo] = SequenceNumber.ZERO,
  uriId:Id[NormalizedURI],
  url:String,
  name:Option[String]    = None,
  caption:Option[String] = None,
  width:Option[Int]      = None,
  height:Option[Int]     = None,
  size:Option[Int]       = None
) extends ModelWithState[ImageInfo] with ModelWithSeqNumber[ImageInfo] with ImageGenericInfo {
  def withId(imageInfoId:Id[ImageInfo]) = copy(id = Some(imageInfoId))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object ImageInfo {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ImageInfo]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[ImageInfo]) and
    (__ \ 'seq).format(SequenceNumber.format[ImageInfo]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'url).format[String] and
    (__ \ 'name).formatNullable[String] and
    (__ \ 'caption).formatNullable[String] and
    (__ \ 'width).formatNullable[Int] and
    (__ \ 'height).formatNullable[Int] and
    (__ \ 'size).formatNullable[Int]
  )(ImageInfo.apply _, unlift(ImageInfo.unapply))
}

