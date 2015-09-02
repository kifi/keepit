package com.keepit.integrity

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ KeepCommander, KeepToLibraryCommander, KeepToUserCommander }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.model._

import scala.concurrent.{ Future, ExecutionContext }

@Singleton
class KeepChecker @Inject() (
    airbrake: AirbrakeNotifier,
    clock: Clock,
    db: Database,
    keepRepo: KeepRepo,
    keepCommander: KeepCommander,
    ktlRepo: KeepToLibraryRepo,
    ktuRepo: KeepToUserRepo,
    ktlCommander: KeepToLibraryCommander,
    ktuCommander: KeepToUserCommander,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    organizationRepo: OrganizationRepo,
    systemValueRepo: SystemValueRepo,
    implicit val executionContext: ExecutionContext) extends Logging {

  private[this] val lock = new AnyRef
  private val timeSlicer = new TimeSlicer(clock)

  private[integrity] val KEEP_INTEGRITY_SEQ = Name[SequenceNumber[Keep]]("keep_integrity_plugin")
  private val KEEP_FETCH_SIZE = 100

  @AlertingTimer(30 seconds)
  @StatsdTiming("keepChecker.check")
  def check(): Unit = lock.synchronized {
    db.readWrite { implicit s =>
      val lastSeq = systemValueRepo.getSequenceNumber(KEEP_INTEGRITY_SEQ).getOrElse(SequenceNumber.ZERO[Keep])
      val keeps = keepRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE)
      if (keeps.nonEmpty) {
        keeps.foreach { keep =>
          ensureUriIntegrity(keep.id.get)
          ensureStateIntegrity(keep.id.get)
          ensureLibrariesHashIntegrity(keep.id.get)
          ensureParticipantsHashIntegrity(keep.id.get)
        }
        systemValueRepo.setSequenceNumber(KEEP_INTEGRITY_SEQ, keeps.map(_.seq).max)
      }
    }
  }

  private def ensureUriIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.get(keepId)
    val allKtus = ktuRepo.getAllByKeepId(keepId, excludeStateOpt = None)
    val allKtls = ktlRepo.getAllByKeepId(keepId, excludeStateOpt = None)
    for (ktu <- allKtus) {
      if (ktu.uriId != keep.uriId) {
        airbrake.notify(s"[KTU-URI-MATCH] KTU ${ktu.id.get}'s URI Id (${ktu.uriId}) does not match keep ${keep.id.get}'s URI id (${keep.uriId})")
        ktuCommander.syncWithKeep(ktu, keep)
      }
    }
    for (ktl <- allKtls) {
      if (ktl.uriId != keep.uriId) {
        airbrake.notify(s"[KTL-URI-MATCH] KTL ${ktl.id.get}'s URI Id (${ktl.uriId}) does not match keep ${keep.id.get}'s URI id (${keep.uriId})")
        ktlCommander.syncWithKeep(ktl, keep)
      }
    }
  }

  private def ensureStateIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.get(keepId)
    val allKtus = ktuRepo.getAllByKeepId(keepId, excludeStateOpt = None)
    val allKtls = ktlRepo.getAllByKeepId(keepId, excludeStateOpt = None)
    if (keep.isInactive) {
      val zombieKtus = allKtus.filter(_.isActive)
      for (ktu <- zombieKtus) {
        airbrake.notify(s"[KTU-STATE-MATCH] KTU ${ktu.id.get} (keep ${ktu.keepId} --- user ${ktu.userId}) is a zombie!")
        ktuCommander.deactivate(ktu)
      }

      val zombieKtls = allKtls.filter(_.isActive)
      for (ktl <- zombieKtls) {
        airbrake.notify(s"[KTL-STATE-MATCH] KTL ${ktl.id.get} (keep ${ktl.keepId} --- lib ${ktl.libraryId}) is a zombie!")
        ktlCommander.deactivate(ktl)
      }
    }
  }

  private def ensureLibrariesHashIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.getNoCache(keepId)
    val libraries = ktlRepo.getAllByKeepId(keepId).map(_.libraryId).toSet
    val expectedHash = LibrariesHash(libraries)
    if (keep.librariesHash != Some(expectedHash)) {
      // TODO(ryan): once we have backfilled and made the field not nullable, uncomment this line
      // airbrake.notify(s"[KTL-HASH-MATCH] Keep $keepId's library hash (${keep.librariesHash}) != $libraries ($expectedHash)")
      keepCommander.refreshLibrariesHash(keep)
    }
  }

  private def ensureParticipantsHashIntegrity(keepId: Id[Keep]) = db.readWrite { implicit session =>
    val keep = keepRepo.getNoCache(keepId)
    val users = ktuRepo.getAllByKeepId(keepId).map(_.userId).toSet
    val expectedHash = ParticipantsHash(users)
    if (keep.participantsHash != Some(expectedHash)) {
      // TODO(ryan): once we have backfilled and made the field not nullable, uncomment this line
      // airbrake.notify(s"[KTU-HASH-MATCH] Keep $keepId's participants hash (${keep.participantsHash}) != $users ($expectedHash)")
      keepCommander.refreshParticipantsHash(keep)
    }
  }
}
