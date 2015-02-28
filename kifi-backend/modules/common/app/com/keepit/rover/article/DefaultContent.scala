package com.keepit.rover.article

import com.keepit.model.PageAuthor
import com.kifi.macros.json
import org.joda.time.DateTime

@json
case class DefaultContent(
  title: Option[String],
  description: Option[String],
  content: Option[String],
  keywords: Seq[String],
  authors: Seq[PageAuthor],
  publishedAt: Option[DateTime]) extends ArticleContent

@json
case class DefaultContext(
  destinationUrl: String,
  http: HTTPContext,
  normalization: NormalizationContext) extends ArticleContext with HTTPContextProvider with NormalizationContextProvider
