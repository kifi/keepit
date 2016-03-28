package com.keepit.shoebox.data.keep

import play.api.libs.json.{ Json, Writes }

case class NewKeepView(
  keep: NewKeepInfo,
  page: Option[NewPageInfo])

object NewKeepView {
  implicit val writes: Writes[NewKeepView] = Json.writes[NewKeepView]
}

