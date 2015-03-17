package com.keepit.rover.article

import com.keepit.model.PageAuthor
import com.kifi.macros.json
import org.joda.time.DateTime

@json
case class DefaultContent(
  normalization: NormalizationInfo) extends ArticleContent with HttpInfoHolder with NormalizationInfoHolder
    destinationUrl: String,
    title: Option[String],
    description: Option[String],
    content: Option[String],
    keywords: Seq[String],
    authors: Seq[PageAuthor],
    openGraphType: Option[String],
    publishedAt: Option[DateTime],
    http: HttpInfo,
  def mediaType = openGraphType
}
