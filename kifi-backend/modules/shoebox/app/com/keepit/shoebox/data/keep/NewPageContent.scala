package com.keepit.shoebox.data.keep

import com.keepit.model.{ BasicLibrary, Hashtag, SourceAttribution }
import com.keepit.rover.article.content.PageAuthor
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json.{ Json, Writes }

case class NewPageInfo(
    content: Option[NewPageContent],
    context: Option[NewPageContext]) {
  def nonEmpty: Boolean = content.nonEmpty || context.nonEmpty
}

object NewPageInfo {
  implicit val writes: Writes[NewPageInfo] = Json.writes[NewPageInfo]
}

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

case class NewPageContext(
  numVisibleKeeps: Int,
  numTotalKeeps: Int,
  // sources: Seq[SourceAttribution], // TODO(ryan): figure out how to retrieve
  // numVisibleSources: Int,
  // numTotalSources: Int,
  keepers: Seq[BasicUser],
  numVisibleKeepers: Int,
  numTotalKeepers: Int,
  libraries: Seq[BasicLibrary],
  numVisibleLibraries: Int,
  numTotalLibraries: Int,
  tags: Seq[Hashtag],
  numVisibleTags: Int)

object NewPageContext {
  private implicit val sourceWrites = SourceAttribution.externalWrites
  implicit val writes: Writes[NewPageContext] = Json.writes[NewPageContext]
}
