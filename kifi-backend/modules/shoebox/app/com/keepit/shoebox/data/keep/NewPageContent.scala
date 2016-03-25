package com.keepit.shoebox.data.keep

import com.keepit.rover.article.content.PageAuthor
import com.keepit.rover.model.ArticleVersion
import org.joda.time.DateTime

case class NewPageContent(
  summary: NewPageSummary,
  history: Seq[NewPageSummary])

case class NewPageSummary(
  author: Option[PageAuthor],
  siteName: Option[String],
  publishedAt: Option[DateTime],
  description: Option[String],
  wordCount: Int,
  fetchedAt: DateTime,
  version: ArticleVersion)

