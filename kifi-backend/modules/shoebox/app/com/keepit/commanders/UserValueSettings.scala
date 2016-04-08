package com.keepit.commanders

import com.keepit.model.{ LibraryOrdering, UserValueName }
import play.api.libs.json.{ JsBoolean, JsObject, JsString, JsValue }

case class UserValueSettings(showFollowedLibraries: Boolean, leftHandRailSort: LibraryOrdering)

object UserValueSettings {

  def readFromJsValue(body: JsValue): UserValueSettings = {
    val showFollowedLibrariesOpt = (body \ "showFollowedLibraries").asOpt[Boolean].orElse((body \ "show_followed_libraries").asOpt[Boolean]) //for backward compatibility
    val leftHandRailSortOpt = (body \ "leftHandRailSort").asOpt[String]
    val showFollowedLibraries = showFollowedLibrariesOpt.getOrElse(true)
    val leftHandRailSort = leftHandRailSortOpt.flatMap(LibraryOrdering.fromStr).getOrElse(LibraryOrdering.LAST_KEPT_INTO)

    UserValueSettings(showFollowedLibraries, leftHandRailSort)
  }

  def writeToJson(settings: UserValueSettings): JsValue = {
    JsObject(Seq(
      "showFollowedLibraries" -> JsBoolean(settings.showFollowedLibraries),
      "leftHandRailSort" -> JsString(settings.leftHandRailSort.value)
    ))
  }
}
