package com.keepit.rover.article

import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.PageAuthor
import com.keepit.scraper.embedly.{ EmbedlyEntity, EmbedlyKeyword, EmbedlyImage }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

class EmbedlyContent(json: JsValue) extends ArticleContent {
  def destinationUrl = (json \ "url").as[String]
  def title = (json \ "title").asOpt[String]
  def description = (json \ "description").asOpt[String]
  def content = rawContent // todo(Léo): needs post-processing to eliminate html tags
  def keywords = embedlyKeywords.map(_.name)
  def authors = (json \ "authors").asOpt[Seq[PageAuthor]] getOrElse Seq.empty[PageAuthor]
  def mediaType = (json \ "type").asOpt[String]
  def publishedAt = (json \ "published").asOpt[DateTime]

  def rawContent = (json \ "content").asOpt[String]
  def isSafe = (json \ "safe").asOpt[Boolean]
  def language = (json \ "language").asOpt[String]
  def images = (json \ "images").as[Seq[EmbedlyImage]]
  def embedlyKeywords = (json \ "keywords").as[Seq[EmbedlyKeyword]]
  def entities = (json \ "entities").as[Seq[EmbedlyEntity]]
  def faviconUrl = (json \ "favicon_url").asOpt[String]
  def media = (json \ "media").asOpt[EmbedlyMedia]
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
