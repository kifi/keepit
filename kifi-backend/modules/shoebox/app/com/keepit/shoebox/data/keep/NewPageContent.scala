package com.keepit.shoebox.data.keep

import com.keepit.rover.article.content.PageAuthor
import com.keepit.rover.model.ArticleVersion
import org.joda.time.DateTime
import play.api.libs.json.{ Writes, Json }

case class NewPageContent(
  summary: NewPageSummary,
  history: Seq[NewPageSummary])

object NewPageContent {
  implicit val writes: Writes[NewPageContent] = Json.writes[NewPageContent]
}

case class NewPageSummary(
  authors: Seq[PageAuthor],
  siteName: Option[String],
  publishedAt: Option[DateTime],
  description: Option[String],
  wordCount: Option[Int])

object NewPageSummary {
  implicit val writes: Writes[NewPageSummary] = Json.writes[NewPageSummary]
}
