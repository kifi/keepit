package com.keepit.common.util

import com.keepit.common.db.Id
import com.keepit.common.json.SchemaReads
import play.api.libs.json.{ JsString, Reads, Writes }

// A PaginationContext is, in practice, a Set[Id[T]]
// The set is compressed and encoded in base64 for performance and security, then wrapped in a QueryContext
final case class PaginationContext[T](value: String) extends AnyVal {
  def toSet = PaginationContext.toSet[T](this)
}
object PaginationContext {
  def fromSet[T](ids: Set[Id[T]]): PaginationContext[T] = PaginationContext(LongSetIdFilter.fromSetToBase64(ids.map(_.id)))
  private def toSet[T](pc: PaginationContext[T]): Set[Id[T]] = LongSetIdFilter.fromBase64ToSet(pc.value).map(Id[T](_))

  def empty[T] = fromSet(Set.empty[Id[T]])
  implicit def sreads[T]: SchemaReads[PaginationContext[T]] = SchemaReads.trivial[String]("pagination context").map(PaginationContext(_))
  implicit def reads[T]: Reads[PaginationContext[T]] = sreads[T].reads
  implicit val writes: Writes[PaginationContext[_]] = Writes { pc => JsString(pc.value) }
}
