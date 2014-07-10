package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class DuplicateDocument(
    id: Option[Id[DuplicateDocument]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    uri1Id: Id[NormalizedURI],
    uri2Id: Id[NormalizedURI],
    percentMatch: Double,
    state: State[DuplicateDocument] = DuplicateDocumentStates.NEW) extends ModelWithState[DuplicateDocument] {

  assert(uri1Id.id < uri2Id.id, "uri1Id ≥ uri2Id")

  def withId(id: Id[DuplicateDocument]) = this.copy(id = Some(id))
  def withState(newState: State[DuplicateDocument]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

}

object DuplicateDocumentStates {
  val NEW = State[DuplicateDocument]("new")
  val MERGED = State[DuplicateDocument]("merged")
  val IGNORED = State[DuplicateDocument]("ignored")
  val UNSCRAPABLE = State[DuplicateDocument]("unscrapable")
}
