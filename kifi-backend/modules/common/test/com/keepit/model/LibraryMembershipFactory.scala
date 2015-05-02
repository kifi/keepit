package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ Id, State }
import com.keepit.common.time._

object LibraryMembershipFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def membership(): PartialLibraryMembership = {
    new PartialLibraryMembership(LibraryMembership(id = Some(Id[LibraryMembership](idx.incrementAndGet())),
      libraryId = Id[Library](idx.incrementAndGet()), userId = Id[User](idx.incrementAndGet()),
      access = LibraryAccess.OWNER, lastJoinedAt = Some(currentDateTime)))
  }

  def memberships(count: Int): Seq[PartialLibraryMembership] = List.fill(count)(membership())

  class PartialLibraryMembership private[LibraryMembershipFactory] (membership: LibraryMembership) {
    def withId(id: Id[LibraryMembership]) = new PartialLibraryMembership(membership.copy(id = Some(id)))
    def withId(id: Int) = new PartialLibraryMembership(membership.copy(id = Some(Id[LibraryMembership](id))))
    def withLibraryOwner(lib: Library) = new PartialLibraryMembership(membership.copy(userId = lib.ownerId, libraryId = lib.id.get))
    def withLibraryFollower(lib: Library, userId: Id[User]) = new PartialLibraryMembership(membership.copy(userId = userId, libraryId = lib.id.get, access = LibraryAccess.READ_ONLY))
    def withLibraryFollower(lib: Id[Library], userId: Id[User]) = new PartialLibraryMembership(membership.copy(userId = userId, libraryId = lib, access = LibraryAccess.READ_ONLY))
    def withLibraryFollower(lib: Library, user: User) = new PartialLibraryMembership(membership.copy(userId = user.id.get, libraryId = lib.id.get, access = LibraryAccess.READ_ONLY))
    def withLibraryCollaborator(libId: Id[Library], userId: Id[User], access: LibraryAccess) = new PartialLibraryMembership(membership.copy(userId = userId, libraryId = libId, access = access))
    def withLibraryCollaborator(lib: Library, user: User, access: LibraryAccess) = new PartialLibraryMembership(membership.copy(userId = user.id.get, libraryId = lib.id.get, access = access))
    def withUser(id: Int) = new PartialLibraryMembership(membership.copy(userId = Id[User](id)))
    def withUser(id: Id[User]) = new PartialLibraryMembership(membership.copy(userId = id))
    def withUser(user: User) = new PartialLibraryMembership(membership.copy(userId = user.id.get))
    def unlisted() = new PartialLibraryMembership(membership.copy(listed = false))
    def fromLibraryInvite(invite: LibraryInvite) = new PartialLibraryMembership(membership.copy(userId = invite.userId.get, libraryId = invite.libraryId, access = invite.access))
    def withState(state: State[LibraryMembership]) = new PartialLibraryMembership(membership.copy(state = state))
    def get: LibraryMembership = membership
  }

  implicit class PartialLibraryMembershipSeq(users: Seq[PartialLibraryMembership]) {
    def get: Seq[LibraryMembership] = users.map(_.get)
  }

}
