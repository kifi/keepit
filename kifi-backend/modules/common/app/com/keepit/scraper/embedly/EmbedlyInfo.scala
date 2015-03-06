package com.keepit.scraper.embedly

import com.keepit.rover.article.{ EmbedlyEntity, EmbedlyKeyword, EmbedlyImage }
import play.api.libs.functional.syntax._
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.libs.json._
import com.keepit.common.time.DateTimeJsonFormat
import org.joda.time.DateTime

case class EmbedlyInfo(
    originalUrl: String,
    url: Option[String],
    title: Option[String],
    description: Option[String],
    authors: Seq[PageAuthor],
    published: Option[DateTime],
    content: Option[String],
    safe: Option[Boolean],
    lang: Option[String],
    faviconUrl: Option[String],
    images: Seq[EmbedlyImage],
    entities: Seq[EmbedlyEntity],
    keywords: Seq[EmbedlyKeyword]) {
  implicit def toPageInfo(nuriId: Id[NormalizedURI]): PageInfo =
    PageInfo(
      id = None,
      uriId = nuriId,
      title = this.title,
      description = this.description.orElse(Some("")),
      authors = authors,
      publishedAt = published,
      safe = this.safe,
      lang = this.lang,
      faviconUrl = (this.faviconUrl.collect { case f: String if f.startsWith("http") => f }) // embedly bug
    )

  def buildImageInfo(nUriId: Id[NormalizedURI], path: String, name: String) = {
    images.zipWithIndex flatMap {
      case (embedlyImage, priority) =>
        Some(embedlyImage.toImageInfoWithPriority(nUriId, Some(priority), path = path, name = name))
    }
  }
}

object EmbedlyInfo {
  val EMPTY = EmbedlyInfo("", None, None, None, Seq.empty, None, None, None, None, None, Seq(), Seq(), Seq())

  implicit val idFormat = Id.format[NormalizedURI]

  implicit val format = (
    (__ \ 'original_url).format[String] and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    OFormat((__ \ 'authors).read[Seq[PageAuthor]] orElse Reads.pure(Seq.empty[PageAuthor]), (__ \ 'authors).write[Seq[PageAuthor]]) and
    OFormat((__ \ 'published).readNullable[DateTime] orElse Reads.pure(None), (__ \ 'published).writeNullable[DateTime]) and
    (__ \ 'content).formatNullable[String] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'language).formatNullable[String] and
    (__ \ 'favicon_url).formatNullable[String] and
    (__ \ 'images).format[Seq[EmbedlyImage]] and
    (__ \ 'entities).format[Seq[EmbedlyEntity]] and
    (__ \ 'keywords).format[Seq[EmbedlyKeyword]]
  )(EmbedlyInfo.apply _, unlift(EmbedlyInfo.unapply))
}

case class StoredEmbedlyInfo(
  uriId: Id[NormalizedURI],
  calledEmbedlyAt: DateTime,
  info: EmbedlyInfo)

object StoredEmbedlyInfo {
  implicit val idFormat = Id.format[NormalizedURI]
  implicit val format = (
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'calledEmbedlyAt).format[DateTime] and
    (__ \ 'info).format[EmbedlyInfo]
  )(StoredEmbedlyInfo.apply _, unlift(StoredEmbedlyInfo.unapply))
}
