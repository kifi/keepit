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
  def images:Seq[ImageInfo]
}

trait PageGenericInfo {
  def description:Option[String]
  // def content:Option[String]
  def faviconUrl:Option[String]
}

object PageInfoStates extends States[PageInfo]

case class PageInfo(
  id:Option[Id[PageInfo]],
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state:     State[PageInfo] = PageInfoStates.ACTIVE,
  seq:       SequenceNumber[PageInfo] = SequenceNumber.ZERO,
  uriId:Id[NormalizedURI],
  description:Option[String],
  safe:Option[Boolean],
  faviconUrl:Option[String],
  imageAvail:Option[Boolean],
  screenshotAvail:Option[Boolean]
) extends ModelWithState[PageInfo] with ModelWithSeqNumber[PageInfo] with PageGenericInfo with PageSafetyInfo {
  def withId(pageInfoId: Id[PageInfo]) = copy(id = Some(pageInfoId))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
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
    (__ \ 'image_avail).formatNullable[Boolean] and
    (__ \ 'screenshot_avail).formatNullable[Boolean]
  )(PageInfo.apply _, unlift(PageInfo.unapply))
}

case class ImageInfo(
  url:String,
  caption:Option[String],
  height:Option[Int],
  width:Option[Int],
  size:Option[Int])

object ImageInfo {
  implicit val format = (
    (__ \ 'url).format[String] and
    (__ \ 'caption).formatNullable[String] and
    (__ \ 'height).formatNullable[Int] and
    (__ \ 'weight).formatNullable[Int] and
    (__ \ 'size).formatNullable[Int]
    )(ImageInfo.apply _, unlift(ImageInfo.unapply))
}

