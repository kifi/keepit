package com.keepit.cortex.dbmodel

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.view.LibraryMembershipView
import org.joda.time.DateTime

case class CortexLibraryMembership(
    id: Option[Id[CortexLibraryMembership]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    membershipId: Id[LibraryMembership],
    libraryId: Id[Library],
    userId: Id[User],
    access: LibraryAccess,
    memberSince: DateTime,
    state: State[CortexLibraryMembership],
    seq: SequenceNumber[CortexLibraryMembership]) extends ModelWithState[CortexLibraryMembership] with ModelWithSeqNumber[CortexLibraryMembership] {
  def withId(id: Id[CortexLibraryMembership]): CortexLibraryMembership = copy(id = Some(id))
  def withUpdateTime(now: DateTime): CortexLibraryMembership = copy(updatedAt = now)
}

object CortexLibraryMembership {
  implicit def fromLibMemState(state: State[LibraryMembership]) = State[CortexLibraryMembership](state.value)
  implicit def fromLibMemSeq(seq: SequenceNumber[LibraryMembership]) = SequenceNumber[CortexLibraryMembership](seq.value)
  def fromLibraryMembershipView(mem: LibraryMembershipView): CortexLibraryMembership =
    CortexLibraryMembership(membershipId = mem.id.get, libraryId = mem.libraryId, userId = mem.userId, access = mem.access, memberSince = mem.createdAt, state = mem.state, seq = mem.seq)
}
