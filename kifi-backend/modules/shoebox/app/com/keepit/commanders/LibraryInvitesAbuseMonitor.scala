package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.model.{ LibraryInviteStates, Library, LibraryInviteRepo, User }

class LibraryInviteAbuseMonitor(
    absoluteWarn: Int,
    absoluteError: Int,
    libraryInvitesRepo: LibraryInviteRepo,
    db: Database,
    airbrake: AirbrakeNotifier) extends Logging {

  if (absoluteWarn >= absoluteError) throw new IllegalStateException(s"absolute warn $absoluteWarn is larger then error $absoluteError")

  def inspect(ownerId: Id[User], userId: Id[User], libraryId: Id[Library], numNewInvites: Int): Unit = {
    val excludeSet = Set(LibraryInviteStates.INACTIVE, LibraryInviteStates.ACCEPTED, LibraryInviteStates.DECLINED)
    val numExistingInvites = db.readOnlyReplica { implicit s => libraryInvitesRepo.countWithLibraryIdAndUserId(libraryId, userId, excludeSet) }
    val afterAdding = numNewInvites + numExistingInvites
    if (afterAdding > absoluteError) {
      throw new AbuseMonitorException(s"user $ownerId tried to invite $userId $numNewInvites times while having $numExistingInvites. max allowed is $absoluteError")
    }
    if (afterAdding > absoluteWarn) {
      airbrake.notify(AirbrakeError(message = Some(s"user $ownerId tried to invite $userId $numNewInvites times while having $numExistingInvites. warning threshold is $absoluteWarn"), userId = Some(ownerId)))
    }
  }
}
