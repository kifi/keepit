package com.keepit.model

import com.keepit.common.cache._
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.rover.article.content.EmbedlyKeyword
import com.kifi.macros.json
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.store.ImageSize
import scala.concurrent.duration.Duration

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
