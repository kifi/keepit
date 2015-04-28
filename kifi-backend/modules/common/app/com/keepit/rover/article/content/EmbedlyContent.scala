package com.keepit.rover.article.content

import com.keepit.common.db.Id
import com.keepit.common.store.ImagePath
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model._
import com.keepit.rover.article.EmbedlyArticle
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

class EmbedlyContent(val json: JsValue) extends ArticleContent[EmbedlyArticle] {
  private val parsed = json.as[EmbedlyContent.ParsedFields]
  def destinationUrl = parsed.url
  def title = parsed.title
  def description = parsed.description
  def content = parsed.content // todo(Léo): needs post-processing to eliminate html tags
  def keywords = parsed.keywords.map(_.name)
  def authors = parsed.authors
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
  def toImageInfoWithPriority(nuriId: Id[NormalizedURI], priority: Option[Int], path: ImagePath, name: String): ImageInfo = {
    ImageInfo(uriId = nuriId, url = Some(this.url), caption = this.caption, width = this.width, height = this.height,
      size = this.size, provider = Some(ImageProvider.EMBEDLY), priority = priority, path = path, name = name)
  }

  def toImageInfo(nuriId: Id[NormalizedURI], path: ImagePath, name: String): ImageInfo = toImageInfoWithPriority(nuriId, None, path = path, name = name)
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
    authors: Seq[PageAuthor],
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
    implicit val reads: Reads[ParsedFields] = (
      (__ \ 'original_url).read[String] and
      (__ \ 'url).read[String] and
      (__ \ 'title).readNullable[String] and
      (__ \ 'description).readNullable[String] and
      (__ \ 'content).readNullable[String] and
      ((__ \ 'authors).read[Seq[PageAuthor]] orElse Reads.pure(Seq.empty[PageAuthor])) and
      (__ \ 'type).readNullable[String] and
      (__ \ 'published).readNullable[DateTime] and
      (__ \ 'safe).readNullable[Boolean] and
      (__ \ 'language).readNullable[String] and
      ((__ \ 'images).read[Seq[EmbedlyImage]] orElse Reads.pure(Seq.empty[EmbedlyImage])) and
      ((__ \ 'keywords).read[Seq[EmbedlyKeyword]] orElse Reads.pure(Seq.empty[EmbedlyKeyword])) and
      ((__ \ 'entities).read[Seq[EmbedlyEntity]] orElse Reads.pure(Seq.empty[EmbedlyEntity])) and
      (__ \ 'favicon_url).readNullable[String] and
      ((__ \ 'media).readNullable[EmbedlyMedia] orElse Reads.pure(None))
    )(ParsedFields.apply _)
  }
  implicit val format: Format[EmbedlyContent] = __.format[JsValue].inmap(new EmbedlyContent(_), _.json)
}
