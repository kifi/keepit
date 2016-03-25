package com.keepit.shoebox.data.keep

import com.keepit.model.KeepPermission
import play.api.libs.json._

case class NewKeepViewerInfo(
  permissions: Set[KeepPermission])

object NewKeepViewerInfo {
  implicit val writes: Writes[NewKeepViewerInfo] = Json.writes[NewKeepViewerInfo]
}
