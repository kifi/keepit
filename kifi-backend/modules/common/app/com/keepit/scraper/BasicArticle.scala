package com.keepit.scraper

import com.keepit.rover.article.Signature

case class BasicArticle(
  title: String,
  content: String,
  description: Option[String] = None,
  canonicalUrl: Option[String] = None,
  alternateUrls: Set[String] = Set.empty[String],
  media: Option[String] = None,
  httpContentType: Option[String] = None, // from http header
  httpOriginalContentCharset: Option[String] = None, // from EntityUtils.getContentCharSet
  destinationUrl: String,
  signature: Signature)

object BasicArticle {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format = (
    (__ \ 'title).format[String] and
    (__ \ 'content).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'canonicalUrl).formatNullable[String] and
    (__ \ 'alternateUrls).format[Set[String]] and
    (__ \ 'media).formatNullable[String] and
    (__ \ 'httpContentType).formatNullable[String] and
    (__ \ 'httpOriginalContentCharset).formatNullable[String] and
    (__ \ 'destinationUrl).format[String] and
    (__ \ 'signature).format[Signature]
  )(BasicArticle.apply, unlift(BasicArticle.unapply))

}
