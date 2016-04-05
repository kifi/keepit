package com.keepit.shoebox.data.keep

import play.api.libs.json.{ Json, Writes }

case class NewKeepInfosForPage(
  page: NewPageInfo,
  keeps: Seq[NewKeepInfo])

object NewKeepInfosForPage {
  implicit val writes: Writes[NewKeepInfosForPage] = Json.writes[NewKeepInfosForPage]
}
