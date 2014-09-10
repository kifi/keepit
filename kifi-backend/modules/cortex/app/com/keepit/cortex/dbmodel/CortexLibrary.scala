package com.keepit.cortex.dbmodel

import com.keepit.common.db._
import com.keepit.model.{ LibraryView, User, LibraryKind, Library }
import org.joda.time.DateTime
import com.keepit.common.time._

case class CortexLibrary(
    id: Option[Id[CortexLibrary]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    libraryId: Id[Library],
    ownerId: Id[User],
    kind: LibraryKind,
    state: State[CortexLibrary],
    seq: SequenceNumber[CortexLibrary]) extends ModelWithState[CortexLibrary] with ModelWithSeqNumber[CortexLibrary] {
  def withId(id: Id[CortexLibrary]): CortexLibrary = copy(id = Some(id))
  def withUpdateTime(now: DateTime): CortexLibrary = copy(updatedAt = now)
}

object CortexLibrary {
  implicit def fromLibraryState(state: State[Library]): State[CortexLibrary] = State[CortexLibrary](state.value)
  implicit def fromLibrarySeq(seq: SequenceNumber[Library]): SequenceNumber[CortexLibrary] = SequenceNumber[CortexLibrary](seq.value)
  def fromLibraryView(lib: LibraryView): CortexLibrary = CortexLibrary(libraryId = lib.id.get, ownerId = lib.ownerId, kind = lib.kind, state = lib.state, seq = lib.seq)
}
