package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ Id, State }
import com.keepit.common.mail.EmailAddress
import org.joda.time.DateTime

object LibraryInviteFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def invite(): PartialLibraryInvite = {
    new PartialLibraryInvite(LibraryInvite(id = Some(Id[LibraryInvite](idx.incrementAndGet())),
      libraryId = Id[Library](idx.incrementAndGet()),
      inviterId = Id[User](idx.incrementAndGet()),
      userId = Some(Id[User](idx.incrementAndGet())),
      access = LibraryAccess.OWNER))
  }

  def invites(count: Int): Seq[PartialLibraryInvite] = List.fill(count)(invite())

  class PartialLibraryInvite private[LibraryInviteFactory] (invitation: LibraryInvite) {
    def withId(id: Id[LibraryInvite]) = new PartialLibraryInvite(invitation.copy(id = Some(id)))
    def withId(id: Int) = new PartialLibraryInvite(invitation.copy(id = Some(Id[LibraryInvite](id))))
    def fromLibraryOwner(lib: Library) = new PartialLibraryInvite(invitation.copy(inviterId = lib.ownerId, libraryId = lib.id.get))
    def declined() = withState(LibraryInviteStates.DECLINED)
    def accepted() = withState(LibraryInviteStates.ACCEPTED)
    def inactive() = withState(LibraryInviteStates.INACTIVE)
    def toUser(id: Int) = new PartialLibraryInvite(invitation.copy(userId = Some(Id[User](id))))
    def toUser(id: Id[User]) = new PartialLibraryInvite(invitation.copy(userId = Some(id)))
    def toUser(user: User) = new PartialLibraryInvite(invitation.copy(userId = Some(user.id.get)))
    def toEmail(email: EmailAddress) = new PartialLibraryInvite(invitation.copy(userId = None, emailAddress = Some(email)))
    def withState(state: State[LibraryInvite]) = new PartialLibraryInvite(invitation.copy(state = state))
    def withCreatedAt(createdAt: DateTime) = new PartialLibraryInvite(invitation.copy(createdAt = createdAt))
    def withLastReminderSentAt(sentAt: DateTime) = new PartialLibraryInvite(invitation.copy(lastReminderSentAt = Some(sentAt)))
    def get: LibraryInvite = invitation
  }

  implicit class PartialLibraryInviteSeq(users: Seq[PartialLibraryInvite]) {
    def get: Seq[LibraryInvite] = users.map(_.get)
  }

}
