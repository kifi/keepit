package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ LibraryInviteStates, Library, LibraryInviteRepo, User }

class LibraryInvitesAbuseMonitor(
    absoluteWarn: Int,
    absoluteError: Int,
    libraryInviteRepo: LibraryInviteRepo,
    db: Database,
    airbrake: AirbrakeNotifier) extends Logging {

  if (absoluteWarn >= absoluteError) throw new IllegalStateException(s"absolute warn $absoluteWarn is larger then error $absoluteError")

  def inspect(inviterId: Id[User], inviteeUserId: Option[Id[User]], email: Option[EmailAddress], libraryId: Id[Library], numNewInvites: Int) {
    val excludeSet = Set(LibraryInviteStates.INACTIVE, LibraryInviteStates.ACCEPTED, LibraryInviteStates.DECLINED)
    val numExistingInvites = db.readOnlyReplica { implicit s =>
      (inviteeUserId, email) match {
        case (Some(id), _) =>
          libraryInviteRepo.countWithLibraryIdAndUserId(libraryId, id, excludeSet)
        case (None, Some(email)) =>
          libraryInviteRepo.countWithLibraryIdAndEmail(libraryId, email, excludeSet)
        case _ =>
          0
      }
    }
    val afterAdding = numNewInvites + numExistingInvites
    if (afterAdding > absoluteError) {
      // Aw, the thread calling this is totally not expecting an exception.
      throw new AbuseMonitorException(s"user $inviterId tried to invite $inviteeUserId $numNewInvites times while having $numExistingInvites. max allowed is $absoluteError")
    }
    if (afterAdding > absoluteWarn) {
      airbrake.notify(AirbrakeError(message = Some(s"user $inviterId tried to invite $inviteeUserId $numNewInvites times while having $numExistingInvites. warning threshold is $absoluteWarn"), userId = Some(inviterId)))
    }
  }
}
