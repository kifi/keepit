package com.keepit.shoebox.data.keep

import com.keepit.model.{ Hashtag, BasicLibrary, SourceAttribution }
import com.keepit.social.BasicUser

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

