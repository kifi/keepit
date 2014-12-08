package com.keepit.search

import com.keepit.common.db.{ Id, State }
import com.keepit.model.NormalizedURI
import play.api.libs.functional.syntax._
import play.api.libs.json._
import org.joda.time.DateTime
import com.keepit.common.time.DateTimeJsonFormat

case class Article(
  id: Id[NormalizedURI],
  title: String,
  content: String,
  description: Option[String],
  author: Option[String],
  publishedAt: Option[DateTime],
  canonicalUrl: Option[String],
  alternateUrls: Set[String],
  keywords: Option[String],
  media: Option[String],
  scrapedAt: DateTime,
  httpContentType: Option[String], // from http header
  httpOriginalContentCharset: Option[String], // from EntityUtils.getContentCharSet
  state: State[NormalizedURI],
  message: Option[String],
  titleLang: Option[Lang],
  contentLang: Option[Lang],
  destinationUrl: Option[String] = None)

object Article {

  implicit val format = (
    (__ \ 'normalizedUriId).format(Id.format[NormalizedURI]) and
    (__ \ 'title).format[String] and
    (__ \ 'content).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'author).formatNullable[String] and
    (__ \ 'publishedAt).formatNullable[DateTime] and
    (__ \ 'canonicalUrl).formatNullable[String] and
    (__ \ 'alternateUrls).formatNullable[Set[String]].inmap[Set[String]](_.getOrElse(Set.empty), Some(_)) and
    (__ \ 'keywords).formatNullable[String] and
    (__ \ 'media).formatNullable[String] and
    (__ \ 'scrapedAt).format[DateTime] and
    (__ \ 'httpContentType).formatNullable[String] and
    (__ \ 'httpOriginalContentCharset).formatNullable[String] and
    (__ \ 'state).format(State.format[NormalizedURI]) and
    (__ \ 'message).formatNullable[String] and
    (__ \ 'titleLang).formatNullable[com.keepit.search.Lang] and
    (__ \ 'contentLang).formatNullable[com.keepit.search.Lang] and
    (__ \ 'destinationUrl).formatNullable[String]
  )(Article.apply _, unlift(Article.unapply))
}
