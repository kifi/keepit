package com.keepit.commanders

import com.keepit.model.{ UserValueName, LibraryOrdering }
import com.kifi.macros.json
import play.api.libs.json.JsValue

@json case class UserValueSettings(showFollowedLibraries: Boolean, leftHandRailSort: LibraryOrdering)

object UserValueSettings {

  def readFromJsValue(body: JsValue): UserValueSettings = {
    val showFollowedLibrariesOpt = (body \ UserValueName.SHOW_FOLLOWED_LIBRARIES.name).asOpt[Boolean]
    val leftHandRailSortOpt = (body \ UserValueName.LEFT_HAND_RAIL_SORT.name).asOpt[String]
    val showFollowedLibraries = showFollowedLibrariesOpt.getOrElse(true)
    val leftHandRailSort = leftHandRailSortOpt.flatMap(LibraryOrdering.fromStr).getOrElse(LibraryOrdering.LAST_KEPT_INTO)

    UserValueSettings(showFollowedLibraries, leftHandRailSort)
  }
}
