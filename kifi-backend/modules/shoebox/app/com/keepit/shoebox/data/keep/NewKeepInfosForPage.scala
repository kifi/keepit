package com.keepit.shoebox.data.keep

import com.keepit.common.util.PaginationContext
import com.keepit.model.Keep
import play.api.libs.json.{ Json, Writes }

case class NewKeepInfosForPage(
  page: Option[NewPageInfo],
  paginationContext: PaginationContext[Keep],
  keeps: Seq[NewKeepInfo])

object NewKeepInfosForPage {
  val empty = NewKeepInfosForPage(page = Option.empty, paginationContext = PaginationContext.empty, keeps = Seq.empty)
  implicit val writes: Writes[NewKeepInfosForPage] = Json.writes[NewKeepInfosForPage]
}
