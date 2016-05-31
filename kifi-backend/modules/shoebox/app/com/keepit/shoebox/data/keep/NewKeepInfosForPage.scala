package com.keepit.shoebox.data.keep

import com.keepit.common.db.Id
import com.keepit.common.json.SchemaReads
import com.keepit.common.util.LongSetIdFilter
import play.api.libs.json.{ JsString, Json, Writes }

case class NewKeepInfosForPage(
  page: Option[NewPageInfo],
  paginationContext: PaginationContext,
  keeps: Seq[NewKeepInfo])

object NewKeepInfosForPage {
  val empty = NewKeepInfosForPage(page = Option.empty, paginationContext = PaginationContext.empty, keeps = Seq.empty)
  implicit val writes: Writes[NewKeepInfosForPage] = Json.writes[NewKeepInfosForPage]
}

// A PaginationContext is, in practice, a Set[Id[T]]
// The set is compressed and encoded in base64 for performance and security, then wrapped in a QueryContext
final case class PaginationContext(value: String) extends AnyVal
object PaginationContext {
  def fromSet[T](ids: Set[Id[T]]): PaginationContext = PaginationContext(LongSetIdFilter.fromSetToBase64(ids.map(_.id)))
  def toSet[T](pc: PaginationContext): Set[Id[T]] = LongSetIdFilter.fromBase64ToSet(pc.value).map(Id[T](_))

  def empty[T] = fromSet(Set.empty[Id[T]])
  implicit val sreads: SchemaReads[PaginationContext] = SchemaReads.trivial[String]("pagination context").map(PaginationContext(_))
  implicit val reads = sreads.reads
  implicit val writes: Writes[PaginationContext] = Writes { pc => JsString(pc.value) }
}
