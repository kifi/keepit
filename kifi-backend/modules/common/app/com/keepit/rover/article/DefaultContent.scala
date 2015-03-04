package com.keepit.rover.article

import com.keepit.model.PageAuthor
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
  mediaType: Option[String],
  publishedAt: Option[DateTime],
  http: HttpInfo,
  normalization: NormalizationInfo) extends ArticleContent with HttpInfoHolder with NormalizationInfoHolder
