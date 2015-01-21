package com.keepit.model

import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.kifi.macros.json
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import com.keepit.common.store.ImageSize
import com.keepit.scraper.embedly.EmbedlyKeyword
import scala.concurrent.duration.Duration

trait PageSafetyInfo {
  def safe: Option[Boolean]
}

trait PageMediaInfo {
  def images: Seq[ImageGenericInfo]
}

trait PageGenericInfo {
  def description: Option[String]
  def authors: Seq[PageAuthor]
  def publishedAt: Option[DateTime]
  // def content:Option[String]
  def faviconUrl: Option[String]
}

// Do not modify - PageAuthor is persisted as Json into the database
case class PageAuthor(name: String, url: Option[String] = None)
object PageAuthor {
  implicit val format = Json.format[PageAuthor]
}

object PageInfoStates extends States[PageInfo]

case class PageInfo(
    id: Option[Id[PageInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PageInfo] = PageInfoStates.ACTIVE,
    seq: SequenceNumber[PageInfo] = SequenceNumber.ZERO,
    uriId: Id[NormalizedURI],
    title: Option[String] = None,
    description: Option[String] = None,
    authors: Seq[PageAuthor] = Seq.empty,
    publishedAt: Option[DateTime] = None,
    safe: Option[Boolean] = None,
    lang: Option[String] = None,
    faviconUrl: Option[String] = None,
    imageInfoId: Option[Id[ImageInfo]] = None) extends ModelWithState[PageInfo] with ModelWithSeqNumber[PageInfo] with PageGenericInfo with PageSafetyInfo {
  def withId(pageInfoId: Id[PageInfo]) = copy(id = Some(pageInfoId))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
  def withImageInfoId(imgInfoId: Id[ImageInfo]) = copy(imageInfoId = Some(imgInfoId))
  override def toString: String = s"PageInfo[id=$id,uri=$uriId]"
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
    (__ \ 'authors).format[Seq[PageAuthor]] and
    (__ \ 'publishedAt).formatNullable[DateTime] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'lang).formatNullable[String] and
    (__ \ 'favicon_url).formatNullable[String] and
    (__ \ 'imageInfoId).formatNullable(Id.format[ImageInfo])
  )(PageInfo.apply _, unlift(PageInfo.unapply))
}

case class PageInfoUriKey(val id: Id[NormalizedURI]) extends Key[PageInfo] {
  override val version = 2
  val namespace = "page_info_by_uri_id"
  def toKey(): String = id.id.toString
}

class PageInfoUriCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[PageInfoUriKey, PageInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object ImageInfoStates extends States[ImageInfo]

trait ImageGenericInfo {
  def url: String
  def caption: Option[String]
  def width: Option[Int]
  def height: Option[Int]
  def size: Option[Int]
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
  val UNKNOWN = ImageProvider("unknown")
  def getProviderIndex(providerOpt: Option[ImageProvider]) = providerOpt map { provider =>
    ImageProvider.providerIndex.get(provider).getOrElse(ImageProvider.providerIndex(ImageProvider.UNKNOWN))
  } getOrElse (0) // Embedly as default
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
  val providerIndex = Map(EMBEDLY -> 0, PAGEPEEKER -> 1, UNKNOWN -> 100)
}

case class ImageFormat(value: String)
object ImageFormat {
  val JPG = ImageFormat("jpg")
  val PNG = ImageFormat("png")
  val GIF = ImageFormat("gif")
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
    path: Option[String] = None) extends ModelWithState[ImageInfo] with ModelWithSeqNumber[ImageInfo] {
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
    (__ \ 'path).formatNullable[String]
  )(ImageInfo.apply _, unlift(ImageInfo.unapply))
}

case class URISummaryRequest(
    uriId: Id[NormalizedURI],
    imageType: ImageType,
    minSize: ImageSize = ImageSize(0, 0),
    withDescription: Boolean,
    waiting: Boolean,
    silent: Boolean) {

  def isCacheable: Boolean = (imageType == ImageType.ANY && minSize == ImageSize(0, 0) && withDescription == true)
}

object URISummaryRequest {
  implicit val format = (
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'imageType).format[ImageType] and
    (__ \ 'width).format[ImageSize] and
    (__ \ 'withDescription).format[Boolean] and
    (__ \ 'waiting).format[Boolean] and
    (__ \ 'silent).format[Boolean]
  )(URISummaryRequest.apply _, unlift(URISummaryRequest.unapply))
}

case class URISummary(
  imageUrl: Option[String] = None,
  title: Option[String] = None,
  description: Option[String] = None,
  imageWidth: Option[Int] = None,
  imageHeight: Option[Int] = None,
  wordCount: Option[Int] = None)

object URISummary {
  implicit val format: Format[URISummary] = (
    (__ \ 'imageUrl).formatNullable[String] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'imageWidth).formatNullable[Int] and
    (__ \ 'imageHeight).formatNullable[Int] and
    (__ \ 'wordCount).formatNullable[Int]
  )(URISummary.apply _, unlift(URISummary.unapply))
}

case class KeywordsSummary(article: Seq[String], embedly: Seq[EmbedlyKeyword], word2vecCosine: Seq[String], word2vecFreq: Seq[String], word2vecWordCount: Int, bestGuess: Seq[String])

@json case class Word2VecKeywords(cosine: Seq[String], freq: Seq[String], wordCounts: Int)
