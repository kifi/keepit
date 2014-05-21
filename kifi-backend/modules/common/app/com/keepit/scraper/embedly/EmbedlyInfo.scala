package com.keepit.scraper.embedly

import play.api.libs.functional.syntax._
import com.keepit.model.PageMediaInfo
import com.keepit.model.PageSafetyInfo
import com.keepit.model.PageInfo
import com.keepit.model.ImageInfo
import com.keepit.model.PageGenericInfo
import com.keepit.model.ImageGenericInfo
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.model.ImageProvider
import play.api.libs.json._
import com.keepit.common.time.DateTimeJsonFormat
import org.joda.time.DateTime



case class EmbedlyImage(
  url:String,
  caption:Option[String] = None,
  width:Option[Int]      = None,
  height:Option[Int]     = None,
  size:Option[Int]       = None) extends ImageGenericInfo {
  def toImageInfoWithPriority(nuriId:Id[NormalizedURI], priority: Option[Int]):ImageInfo = ImageInfo(uriId = nuriId, url = Some(this.url), caption = this.caption, width = this.width, height = this.height, size = this.size, provider = Some(ImageProvider.EMBEDLY), priority = priority)
  implicit def toImageInfo(nuriId:Id[NormalizedURI]):ImageInfo = toImageInfoWithPriority(nuriId, None)
}

object EmbedlyImage {
  implicit val format = (
    (__ \ 'url).format[String] and
    (__ \ 'caption).formatNullable[String] and
    (__ \ 'width).formatNullable[Int] and
    (__ \ 'height).formatNullable[Int] and
    (__ \ 'size).formatNullable[Int]
    )(EmbedlyImage.apply _, unlift(EmbedlyImage.unapply))
}

case class EmbedlyInfo(
  originalUrl:String,
  url:Option[String],
  title:Option[String],
  description:Option[String],
  content:Option[String],
  safe:Option[Boolean],
  lang:Option[String],
  faviconUrl:Option[String],
  images:Seq[EmbedlyImage]) extends PageGenericInfo with PageSafetyInfo with PageMediaInfo {
  implicit def toPageInfo(nuriId:Id[NormalizedURI]):PageInfo =
    PageInfo(
      id = None,
      uriId = nuriId,
      title = this.title,
      description = this.description,
      safe = this.safe,
      lang = this.lang,
      faviconUrl = (this.faviconUrl.collect{ case f:String if f.startsWith("http") => f }) // embedly bug
    )

  def buildImageInfo(nUriId: Id[NormalizedURI]) = {
    images.zipWithIndex flatMap { case (embedlyImage, priority) =>
      Some(embedlyImage.toImageInfoWithPriority(nUriId, Some(priority)))
    }
  }
}

object EmbedlyInfo {
  val EMPTY = EmbedlyInfo("", None, None, None, None, None, None, None, Seq.empty[EmbedlyImage])

  implicit val format = (
    (__ \ 'original_url).format[String] and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'content).formatNullable[String] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'language).formatNullable[String] and
    (__ \ 'favicon_url).formatNullable[String] and
    (__ \ 'images).format[Seq[EmbedlyImage]]
    )(EmbedlyInfo.apply _, unlift(EmbedlyInfo.unapply))

   def fromExtendedEmbedlyInfo(extInfo: EmbedlyInfo): EmbedlyInfo = {
     EmbedlyInfo(extInfo.originalUrl, extInfo.url, extInfo.title, extInfo.description, extInfo.content, extInfo.safe, extInfo.lang, extInfo.faviconUrl, extInfo.images)
  }
}

case class EmbedlyEntity(count: Int, entity: String)
case class EmbedlyKeyword(score: Int, keyword: String)

case class ExtendedEmbedlyInfo(
  uriId: Id[NormalizedURI],
  originalUrl: String,
  url: Option[String],
  title: Option[String],
  description: Option[String],
  content: Option[String],
  safe: Option[Boolean],
  lang: Option[String],
  faviconUrl: Option[String],
  images: Seq[EmbedlyImage],
  entities: Seq[EmbedlyEntity],
  keywords: Seq[EmbedlyKeyword],
  calledEmbedlyAt: DateTime
)

object ExtendedEmbedlyInfo {
  val EMPTY = ExtendedEmbedlyInfo(Id[NormalizedURI](-1), "", None, None, None, None, None, None, None, Seq(), Seq(), Seq(), DateTime.now())

  implicit val idFormat = Id.format[NormalizedURI]
  implicit val entityFormat = Json.format[EmbedlyEntity]
  implicit val keywordFormat = Json.format[EmbedlyKeyword]

  implicit val format = (
    (__ \'uriId).format[Id[NormalizedURI]] and
    (__ \ 'original_url).format[String] and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'content).formatNullable[String] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'language).formatNullable[String] and
    (__ \ 'favicon_url).formatNullable[String] and
    (__ \ 'images).format[Seq[EmbedlyImage]] and
    (__ \'entities).format[Seq[EmbedlyEntity]] and
    (__ \'keywords).format[Seq[EmbedlyKeyword]] and
    (__ \ 'calledEmbedlyAt).format[DateTime]
  )(ExtendedEmbedlyInfo.apply _, unlift(ExtendedEmbedlyInfo.unapply))
}
