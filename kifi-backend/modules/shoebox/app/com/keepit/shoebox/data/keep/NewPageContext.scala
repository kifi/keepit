package com.keepit.shoebox.data.keep

import com.keepit.model.{ Hashtag, BasicLibrary, SourceAttribution }
import com.keepit.social.BasicUser
import play.api.libs.json.{ Writes, Json }

case class NewPageContext(
  keeps: Seq[NewKeepInfo],
  numVisibleKeeps: Int,
  numTotalKeeps: Int,
  sources: Seq[SourceAttribution],
  numVisibleSources: Int,
  numTotalSources: Int,
  keepers: Seq[BasicUser],
  numVisibleKeepers: Int,
  numTotalKeepers: Int,
  libraries: Seq[BasicLibrary],
  numVisibleLibraries: Int,
  numTotalLibraries: Int,
  tags: Seq[Hashtag],
  numVisibleTags: Int,
  numTotalTags: Int)

object NewPageContext {
  private implicit val sourceWrites = SourceAttribution.externalWrites
  implicit val writes: Writes[NewPageContext] = Json.writes[NewPageContext]
}
