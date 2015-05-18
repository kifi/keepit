package com.keepit.model

import play.api.libs.json._
import play.api.libs.functional.syntax._

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
