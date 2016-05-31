package com.keepit.shoebox.data.keep

import com.keepit.common.db.Id
import com.keepit.common.json.SchemaReads
import com.keepit.common.util.LongSetIdFilter
import play.api.libs.json.{ Json, Writes }

case class NewKeepInfosForPage(
  page: Option[NewPageInfo],
  keeps: Seq[NewKeepInfo])

object NewKeepInfosForPage {
  val empty = NewKeepInfosForPage(page = Option.empty, keeps = Seq.empty)
  implicit val writes: Writes[NewKeepInfosForPage] = Json.writes[NewKeepInfosForPage]
}

// A PaginationContext is practically a Set[Id[T]]
// The set is compressed and encoded in base64 for performance and security, then wrapped in a QueryContext
final case class PaginationContext(value: String) extends AnyVal {
  def toSet[T]: Set[Id[T]] = LongSetIdFilter.fromBase64ToSet(value).map(Id[T](_))
}
object PaginationContext {
  implicit val sreads: SchemaReads[PaginationContext] = SchemaReads.trivial[String]("pagination context").map(PaginationContext(_))
  implicit val reads = sreads.reads
}
