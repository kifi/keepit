package com.keepit.rover.article

import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.PageAuthor
import com.keepit.scraper.embedly.{ EmbedlyEntity, EmbedlyKeyword, EmbedlyImage }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

class EmbedlyContent(json: JsValue) extends ArticleContent {
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
}

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
