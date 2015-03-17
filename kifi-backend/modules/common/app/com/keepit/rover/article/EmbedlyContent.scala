package com.keepit.rover.article

import com.keepit.common.db.Id
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

class EmbedlyContent(val json: JsValue) extends ArticleContent {
  private val parsed = json.as[EmbedlyContent.ParsedFields]
  def destinationUrl = parsed.url
  def title = parsed.title
  def description = parsed.description
  def content = parsed.content // todo(Léo): needs post-processing to eliminate html tags
  def keywords = parsed.keywords.map(_.name)
  def authors = parsed.authors getOrElse Seq.empty[PageAuthor]
  def mediaType = parsed.`type`
  def publishedAt = parsed.published

  def isSafe = parsed.safe
  def language = parsed.language
  def images = parsed.images
  def embedlyKeywords = parsed.keywords
  def entities = parsed.entities
  def faviconUrl = parsed.favicon_url
  def media = parsed.media
}

@json case class EmbedlyEntity(name: String, count: Int)
@json case class EmbedlyKeyword(name: String, score: Int)

case class EmbedlyMedia(mediaType: String, html: String, width: Int, height: Int, url: Option[String])
object EmbedlyMedia {
  implicit val reads: Reads[EmbedlyMedia] = (
    (__ \ 'type).read[String] and
    (__ \ 'html).read[String] and
    (__ \ 'width).read[Int] and
    (__ \ 'height).read[Int] and
    (__ \ 'url).readNullable[String]
  )(EmbedlyMedia.apply _)
}

case class EmbedlyImage(
    url: String,
    caption: Option[String] = None,
    width: Option[Int] = None,
    height: Option[Int] = None,
    size: Option[Int] = None) extends ImageGenericInfo {
  def toImageInfoWithPriority(nuriId: Id[NormalizedURI], priority: Option[Int], path: String, name: String): ImageInfo = {
    ImageInfo(uriId = nuriId, url = Some(this.url), caption = this.caption, width = this.width, height = this.height,
      size = this.size, provider = Some(ImageProvider.EMBEDLY), priority = priority, path = path, name = name)
  }

  def toImageInfo(nuriId: Id[NormalizedURI], path: String, name: String): ImageInfo = toImageInfoWithPriority(nuriId, None, path = path, name = name)
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

object EmbedlyContent {
  case class ParsedFields(
    original_url: String,
    url: String,
    title: Option[String],
    description: Option[String],
    content: Option[String],
    authors: Option[Seq[PageAuthor]],
    `type`: Option[String],
    published: Option[DateTime],
    safe: Option[Boolean],
    language: Option[String],
    images: Seq[EmbedlyImage],
    keywords: Seq[EmbedlyKeyword],
    entities: Seq[EmbedlyEntity],
    favicon_url: Option[String],
    media: Option[EmbedlyMedia])

  object ParsedFields {
    implicit val reads: Reads[ParsedFields] = Json.reads[ParsedFields] // todo(Léo): we may need a more permissive Reads
  }

  implicit val format: Format[EmbedlyContent] = {
    Format(
      Reads(json => JsSuccess(json).map(new EmbedlyContent(_))),
      Writes(_.json)
    )
  }
}
