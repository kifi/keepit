package com.keepit.integrity

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ Hashtags, KeepCommander, KeepToLibraryCommander, KeepToUserCommander }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.Debouncing
import com.keepit.model._
import org.joda.time.Period

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
    libraryRepo: LibraryRepo,
    systemValueRepo: SystemValueRepo,
    collectionRepo: CollectionRepo,
    implicit val executionContext: ExecutionContext) extends Logging {

  private val debouncer = new Debouncing.Dropper[Unit]
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
          ensureStateIntegrity(keep.id.get)
          ensureUriIntegrity(keep.id.get)
          ensureLibrariesIntegrity(keep.id.get)
          ensureParticipantsIntegrity(keep.id.get)
          ensureOrganizationIdIntegrity(keep.id.get)
          ensureNoteAndTagsAreInSync(keep.id.get)
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
    if (keep.isInactive) {
      val zombieKtus = ktuRepo.getAllByKeepId(keepId, excludeStateOpt = Some(KeepToUserStates.INACTIVE))
      for (ktu <- zombieKtus) {
        debouncer.debounce(ktu.userId.id.toString, Period.minutes(1)) { airbrake.notify(s"[KTU-STATE-MATCH] KTU ${ktu.id.get} (keep ${ktu.keepId} --- user ${ktu.userId}) is a zombie!") }
        ktuCommander.deactivate(ktu)
      }

      val zombieKtls = ktlRepo.getAllByKeepId(keepId, excludeStateOpt = Some(KeepToLibraryStates.INACTIVE))
      for (ktl <- zombieKtls) {
        debouncer.debounce(ktl.libraryId.toString, Period.minutes(1)) { airbrake.notify(s"[KTL-STATE-MATCH] KTL ${ktl.id.get} (keep ${ktl.keepId} --- lib ${ktl.libraryId}) is a zombie!") }
        ktlCommander.deactivate(ktl)
      }
    }
  }

  private def ensureLibrariesIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.getNoCache(keepId)
    val libraries = ktlRepo.getAllByKeepId(keepId).map(_.libraryId).toSet
    if (keep.connections.libraries != libraries) {
      log.error(s"[KTL-MATCH] Keep $keepId's libraries don't match: ${keep.connections.libraries} != $libraries")
      keepCommander.refreshLibraries(keepId)
    }
  }

  private def ensureParticipantsIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.getNoCache(keepId)
    val users = ktuRepo.getAllByKeepId(keepId).map(_.userId).toSet
    if (keep.connections.users != users) {
      log.error(s"[KTU-MATCH] Keep $keepId's participants don't match: ${keep.connections.users} != $users")
      keepCommander.refreshParticipants(keepId)
    }
  }

  private def ensureOrganizationIdIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.getNoCache(keepId)
    ktlRepo.getAllByKeepId(keepId).map(_.libraryId).foreach { libId =>
      val library = libraryRepo.get(libId)
      if (keep.organizationId != library.organizationId) keepCommander.syncWithLibrary(keep, library)
    }
  }

  private def ensureNoteAndTagsAreInSync(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.getNoCache(keepId)

    val tagsFromHashtags = Hashtags.findAllHashtagNames(keep.note.getOrElse("")).map(Hashtag.apply)
    val tagsFromCollections = collectionRepo.getHashtagsByKeepId(keep.id.get)
    if (tagsFromHashtags.map(_.normalized) != tagsFromCollections.map(_.normalized) && keep.isActive) {
      // todo: Change to airbrake.notify after we're sure this won't wake people up at night
      log.info(s"[NOTE-TAGS-MATCH] Keep $keepId's note does not match tags. $tagsFromHashtags vs $tagsFromCollections")
      keepCommander.autoFixKeepNoteAndTags(keep.id.get) // Async, max 1 thread system wide
    }

    // We don't want later checkers to overwrite the eventual note, so change the note they see when they load from db
    val newNote = Option(Hashtags.addHashtagsToString(keep.note.getOrElse(""), tagsFromCollections.toSeq)).filter(_.nonEmpty)
    keepRepo.save(keep.withNote(newNote))
  }
}
