package com.keepit.rover.article.content

import com.keepit.rover.article._
import com.kifi.macros.json
import org.joda.time.DateTime

@json
case class DefaultContent(
    destinationUrl: String,
    title: Option[String],
    description: Option[String],
    content: Option[String],
    keywords: Seq[String],
    authors: Seq[PageAuthor],
    openGraphType: Option[String],
    publishedAt: Option[DateTime],
    http: HttpInfo,
    normalization: NormalizationInfo) extends ArticleContent[DefaultArticle] with HttpInfoHolder with NormalizationInfoHolder {
  def contentType = openGraphType
}
