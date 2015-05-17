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
