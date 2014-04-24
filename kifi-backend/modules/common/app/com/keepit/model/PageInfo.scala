package com.keepit.model

import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.json.JsonFormatters._

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
  title:Option[String]            = None,
  description:Option[String]      = None,
  safe:Option[Boolean]            = None,
  lang:Option[String]             = None,
  faviconUrl:Option[String]       = None,
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
    (__ \ 'title).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'lang).formatNullable[String] and
    (__ \ 'favicon_url).formatNullable[String] and
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

case class ImageType(value: String)
object ImageType {
  val IMAGE = ImageType("image")
  val SCREENSHOT = ImageType("screenshot")
  val ANY = ImageType("any")
  implicit val imageTypeFormat = new Format[ImageType] {
    def reads(json: JsValue): JsResult[ImageType] = {
      json.asOpt[String] match {
        case Some(str) => JsSuccess(ImageType(str))
        case None => JsError()
      }
    }
    def writes(kind: ImageType): JsValue = {
      JsString(kind.value)
    }
  }
}

case class ImageProvider(value: String)
object ImageProvider {
  val EMBEDLY = ImageProvider("embedly")
  val PAGEPEEKER = ImageProvider("pagepeeker")
  implicit val imageProviderFormat = new Format[ImageProvider] {
    def reads(json: JsValue): JsResult[ImageProvider] = {
      json.asOpt[String] match {
        case Some(str) => JsSuccess(ImageProvider(str))
        case None => JsError()
      }
    }
    def writes(kind: ImageProvider): JsValue = {
      JsString(kind.value)
    }
  }
}

case class ImageFormat(value: String)
object ImageFormat {
  val JPG = ImageFormat("jpg")
  val PNG = ImageFormat("png")
  val UNKNOWN = ImageFormat("unknown")
  implicit val imageFormatFormat = new Format[ImageFormat] {
    def reads(json: JsValue): JsResult[ImageFormat] = {
      json.asOpt[String] match {
        case Some(str) => JsSuccess(ImageFormat(str))
        case None => JsError()
      }
    }
    def writes(kind: ImageFormat): JsValue = {
      JsString(kind.value)
    }
  }
}

case class ImageInfo(
  id:Option[Id[ImageInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state:     State[ImageInfo] = ImageInfoStates.ACTIVE,
  seq:       SequenceNumber[ImageInfo] = SequenceNumber.ZERO,
  externalId: ExternalId[ImageInfo] = ExternalId(),
  uriId:Id[NormalizedURI],
  url:String,
  name:Option[String]    = None,
  caption:Option[String] = None,
  width:Option[Int]      = None,
  height:Option[Int]     = None,
  size:Option[Int]       = None,
  provider:ImageProvider,
  format: Option[ImageFormat] = None,
  priority: Option[Int] = None
) extends ModelWithState[ImageInfo] with ModelWithExternalId[ImageInfo] with ModelWithSeqNumber[ImageInfo] with ImageGenericInfo {
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
    (__ \ 'externalId).format(ExternalId.format[ImageInfo]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'url).format[String] and
    (__ \ 'name).formatNullable[String] and
    (__ \ 'caption).formatNullable[String] and
    (__ \ 'width).formatNullable[Int] and
    (__ \ 'height).formatNullable[Int] and
    (__ \ 'size).formatNullable[Int] and
    (__ \ 'provider).format[ImageProvider] and
    (__ \ 'format).formatNullable[ImageFormat] and
    (__ \ 'priority).formatNullable[Int]
  )(ImageInfo.apply _, unlift(ImageInfo.unapply))
}

