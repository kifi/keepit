package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.commanders.{ KeepCommander, KeepToLibraryCommander, KeepToUserCommander, LibraryInfoCommander }
import com.keepit.common.core._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ Logging, NamedStatsdTimer }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.model._

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
    systemValueRepo: SystemValueRepo) extends Logging {

  private[this] val lock = new AnyRef
  private val timeSlicer = new TimeSlicer(clock)

  private[integrity] val KEEP_URI_CHANGED = Name[SequenceNumber[Keep]]("keep_integrity_plugin_keep_uri_changed")
  private[integrity] val KEEP_DEACTIVATED = Name[SequenceNumber[Keep]]("keep_integrity_plugin_keep_deactivated")
  private[integrity] val KEEP_LIBRARY_CHANGED = Name[SequenceNumber[Keep]]("keep_integrity_plugin_keep_library_changed")
  private[integrity] val KEEP_PARTICIPANT_CHANGED = Name[SequenceNumber[Keep]]("keep_integrity_plugin_keep_participant_changed")
  private val KEEP_FETCH_SIZE = 100

  private def getLastSeqNum[T](key: Name[SequenceNumber[T]])(implicit session: RSession) = {
    systemValueRepo.getSequenceNumber(key).getOrElse(SequenceNumber.ZERO[T])
  }

  def check(): Unit = lock.synchronized {
    syncOnUriChange() // reads keeps, ensures that uriIds on ktls/ktus are right
    syncOnDeactivate() // reads dead keeps, ensures that state on ktls/ktus are right
    syncOnLibraryChange() // reads keeps, ensures that `librariesHash` is right
    syncOnParticipantChange() // reads keeps, ensures that `participantsHash` is right
  }

  private[integrity] def syncOnUriChange(): Unit = {
    val keeps = db.readOnlyReplica { implicit s =>
      val lastSeq = getLastSeqNum(KEEP_URI_CHANGED)
      keepRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE)
    }

    keeps.foreach { keep =>
      db.readWrite { implicit session => ensureUriIntegrity(keep.id.get) }
    }

    keeps.map(_.seq).maxOpt.foreach { maxSeq =>
      db.readWrite { implicit session => systemValueRepo.setSequenceNumber(KEEP_URI_CHANGED, maxSeq) }
    }
  }

  private[integrity] def syncOnDeactivate(): Unit = {
    val keeps = db.readOnlyReplica { implicit s =>
      val lastSeq = getLastSeqNum(KEEP_DEACTIVATED)
      keepRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE)
    }

    keeps.filter(_.isInactive).foreach { keep =>
      db.readWrite { implicit session => ensureStateIntegrity(keep.id.get) }
    }

    keeps.map(_.seq).maxOpt.foreach { maxSeq =>
      db.readWrite { implicit session => systemValueRepo.setSequenceNumber(KEEP_DEACTIVATED, maxSeq) }
    }
  }

  private[integrity] def syncOnLibraryChange(): Unit = {
    val keeps = db.readOnlyReplica { implicit s =>
      val lastSeq = getLastSeqNum(KEEP_LIBRARY_CHANGED)
      keepRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE)
    }

    keeps.foreach { keep =>
      db.readWrite { implicit session => ensureLibrariesHashIntegrity(keep.id.get) }
    }

    keeps.map(_.seq).maxOpt.foreach { maxSeq =>
      db.readWrite { implicit session => systemValueRepo.setSequenceNumber(KEEP_LIBRARY_CHANGED, maxSeq) }
    }
  }

  private[integrity] def syncOnParticipantChange(): Unit = {
    val keeps = db.readOnlyReplica { implicit s =>
      val lastSeq = getLastSeqNum(KEEP_PARTICIPANT_CHANGED)
      keepRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE)
    }

    keeps.foreach { keep =>
      db.readWrite { implicit session => ensureParticipantsHashIntegrity(keep.id.get) }
    }

    keeps.map(_.seq).maxOpt.foreach { maxSeq =>
      db.readWrite { implicit session => systemValueRepo.setSequenceNumber(KEEP_PARTICIPANT_CHANGED, maxSeq) }
    }
  }

  private def ensureUriIntegrity(keepId: Id[Keep]) = db.readWrite { implicit session =>
    val keep = keepRepo.getNoCache(keepId)

    val allKtus = ktuRepo.getAllByKeepId(keepId, excludeStateOpt = None)
    for (ktu <- allKtus) {
      if (ktu.uriId != keep.uriId) {
        airbrake.notify(s"[KTU-URI-MATCH] KTU ${ktu.id.get}'s URI Id (${ktu.uriId}) does not match keep ${keep.id.get}'s URI id (${keep.uriId})")
        ktuCommander.syncWithKeep(ktu, keep)
      }
    }

    val allKtls = ktlRepo.getAllByKeepId(keepId)
    for (ktl <- allKtls) {
      if (ktl.uriId != keep.uriId) {
        airbrake.notify(s"[KTL-URI-MATCH] KTL ${ktl.id.get}'s URI Id (${ktl.uriId}) does not match keep ${keep.id.get}'s URI id (${keep.uriId})")
        ktlCommander.syncWithKeep(ktl, keep)
      }
    }
  }

  private def ensureStateIntegrity(keepId: Id[Keep]) = db.readWrite { implicit session =>
    val keep = keepRepo.getNoCache(keepId)
    if (keep.isInactive) {
      val zombieKtus = ktuRepo.getAllByKeepId(keepId, excludeStateOpt = Some(KeepToUserStates.INACTIVE))
      for (ktu <- zombieKtus) {
        airbrake.notify(s"[KTU-STATE-MATCH] KTU ${ktu.id.get} (keep ${ktu.keepId} --- user ${ktu.userId}) is a zombie!")
        ktuCommander.deactivate(ktu)
      }

      val zombieKtls = ktlRepo.getAllByKeepId(keepId, excludeStateOpt = Some(KeepToLibraryStates.INACTIVE))
      for (ktl <- zombieKtls) {
        airbrake.notify(s"[KTL-STATE-MATCH] KTL ${ktl.id.get} (keep ${ktl.keepId} --- lib ${ktl.libraryId}) is a zombie!")
        ktlCommander.deactivate(ktl)
      }
    }
  }

  private def ensureLibrariesHashIntegrity(keepId: Id[Keep]) = db.readWrite { implicit session =>
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
