package com.keepit.scraper

case class BasicArticle(
  title: String,
  content: String,
  description: Option[String] = None,
  canonicalUrl: Option[String] = None,
  media: Option[String] = None,
  httpContentType: Option[String] = None, // from http header
  httpOriginalContentCharset: Option[String] = None, // from EntityUtils.getContentCharSet
  destinationUrl: Option[String] = None,
  signature: Signature
)

object BasicArticle {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format = (
    (__ \ 'title).format[String] and
    (__ \ 'content).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'canonicalUrl).formatNullable[String] and
    (__ \ 'media).formatNullable[String] and
    (__ \ 'httpContentType).formatNullable[String] and
    (__ \ 'httpOriginalContentCharset).formatNullable[String] and
    (__ \ 'destinationUrl).formatNullable[String] and
    (__ \ 'signature).format[Signature]
  )(BasicArticle.apply, unlift(BasicArticle.unapply))

}
