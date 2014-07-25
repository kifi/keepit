package com.keepit.model

import org.joda.time.DateTime

case class ScrapeJobStatus(worker: String, submit: DateTime, uri: NormalizedURI, info: ScrapeInfo)

object ScrapeJobStatus {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format = (
    (__ \ 'worker).format[String] and
    (__ \ 'submitTS).format[DateTime] and
    (__ \ 'uri).format[NormalizedURI] and
    (__ \ 'scrape).format[ScrapeInfo]
  )(ScrapeJobStatus.apply _, unlift(ScrapeJobStatus.unapply))
}