package com.keepit.shoebox.data.keep

import com.keepit.model.{ Hashtag, BasicLibrary, SourceAttribution }
import com.keepit.search.augmentation.{ RestrictedKeepInfo, LimitedAugmentationInfo }
import com.keepit.social.BasicUser
import play.api.libs.json.{ Writes, Json }

case class NewPageContext(
  keeps: Seq[RestrictedKeepInfo], // TODO(ryan): should this switch to NewKeepInfo?
  numVisibleKeeps: Int,
  // numTotalKeeps: Int, // TODO(ryan): is it even possible to retrive this information? LimitedAugmentationInfo doesn't have it?
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
// numTotalTags: Int)

object NewPageContext {
  private implicit val sourceWrites = SourceAttribution.externalWrites
  implicit val writes: Writes[NewPageContext] = Json.writes[NewPageContext]
}
