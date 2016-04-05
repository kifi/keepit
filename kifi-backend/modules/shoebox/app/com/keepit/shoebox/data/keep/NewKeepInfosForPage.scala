package com.keepit.shoebox.data.keep

import play.api.libs.json.{ Json, Writes }

case class NewKeepInfosForPage(
  page: Option[NewPageInfo],
  keeps: Seq[NewKeepInfo])

object NewKeepInfosForPage {
  val empty = NewKeepInfosForPage(page = Option.empty, keeps = Seq.empty)
  implicit val writes: Writes[NewKeepInfosForPage] = Json.writes[NewKeepInfosForPage]
}
