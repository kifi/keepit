package com.keepit.integrity

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.Debouncing
import com.keepit.model._
import org.joda.time.{ Period }
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._
import scala.util.Try

@Singleton
class KeepChecker @Inject() (
    airbrake: AirbrakeNotifier,
    clock: Clock,
    db: Database,
    keepRepo: KeepRepo,
    keepCommander: KeepCommander,
    keepMutator: KeepMutator,
    ktlRepo: KeepToLibraryRepo,
    ktuRepo: KeepToUserRepo,
    kteRepo: KeepToEmailRepo,
    ktlCommander: KeepToLibraryCommander,
    ktuCommander: KeepToUserCommander,
    kteCommander: KeepToEmailCommander,
    libraryRepo: LibraryRepo,
    systemValueRepo: SystemValueRepo,
    collectionRepo: CollectionRepo,
    tagCommander: TagCommander,
    implicit val executionContext: ExecutionContext) extends Logging {

  private val debouncer = new Debouncing.Dropper[Unit]
  private[this] val lock = new AnyRef

  private val KEEP_INTEGRITY_SEQ = Name[SequenceNumber[Keep]]("keep_integrity_plugin")
  private val KEEP_INTEGRITY_COUNT = Name[SystemValue]("keep_integrity_plugin_id")
  private val BATCH_SIZE = 250

  @AlertingTimer(120 seconds)
  @StatsdTiming("keepChecker.check")
  def check(): Unit = lock.synchronized {
    // There are two data sources:.
    // • By seq num will catch issues that arise from updates to existing keeps
    // • By keep id will catch issues on new keeps
    db.readWrite { implicit s =>
      val lastKeepId = Id[Keep](systemValueRepo.getValue(KEEP_INTEGRITY_COUNT).flatMap(p => Try(p.toLong).toOption).getOrElse(0L))
      val lastSeq = systemValueRepo.getSequenceNumber(KEEP_INTEGRITY_SEQ).getOrElse(SequenceNumber.ZERO[Keep])

      val recentKeeps = keepRepo.getByIdGreaterThan(lastKeepId, BATCH_SIZE).filter(_.createdAt.isBefore(clock.now.minusSeconds(20)))
      val seqNumKeeps = keepRepo.getBySequenceNumber(lastSeq, BATCH_SIZE - recentKeeps.length)
      val keeps = (recentKeeps ++ seqNumKeeps).distinctBy(_.id.get)

      log.info(s"[KeepChecker] Running keep checker. Recent: ${recentKeeps.length} (${recentKeeps.flatMap(_.id).maxOpt}), seq: ${seqNumKeeps.length} (${seqNumKeeps.map(_.seq).maxOpt}")

      if (keeps.nonEmpty) {
        keeps.foreach { keep => // Best not to use this object directly, because the underlying row gets updated
          ensureStateIntegrity(keep.id.get)
          ensureUriIntegrity(keep.id.get)
          ensureLibrariesIntegrity(keep.id.get)
          ensureParticipantsIntegrity(keep.id.get)
          ensureNoteAndTagsAreInSync(keep.id.get)
        }
        recentKeeps.map(_.id.get.id).maxOpt.foreach { maxId =>
          systemValueRepo.setValue(KEEP_INTEGRITY_COUNT, maxId.toString)
        }
        seqNumKeeps.map(_.seq).maxOpt.foreach { hwm =>
          systemValueRepo.setSequenceNumber(KEEP_INTEGRITY_SEQ, hwm)
        }
      }
    }
  }

  private def ensureUriIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.get(keepId)
    val allKtus = ktuRepo.getAllByKeepId(keepId, excludeStateOpt = None)
    val allKtes = kteRepo.getAllByKeepId(keepId, excludeStateOpt = None)
    val allKtls = ktlRepo.getAllByKeepId(keepId, excludeStateOpt = None)
    for (ktu <- allKtus) {
      if (ktu.uriId != keep.uriId) {
        airbrake.notify(s"[KTU-URI-MATCH] KTU ${ktu.id.get}'s URI Id (${ktu.uriId}) does not match keep ${keep.id.get}'s URI id (${keep.uriId})")
        ktuCommander.syncWithKeep(ktu, keep)
      }
    }
    for (kte <- allKtes) {
      if (kte.uriId != keep.uriId) {
        airbrake.notify(s"[KTE-URI-MATCH] KTE ${kte.id.get}'s URI Id (${kte.uriId}) does not match keep ${keep.id.get}'s URI id (${keep.uriId})")
        kteCommander.syncWithKeep(kte, keep)
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
        debouncer.debounce(ktu.userId.id.toString, 1 minute) { airbrake.notify(s"[KTU-STATE-MATCH] KTU ${ktu.id.get} (keep ${ktu.keepId} --- user ${ktu.userId}) is a zombie!") }
        ktuCommander.deactivate(ktu)
      }

      val zombieKtes = kteRepo.getAllByKeepId(keepId, excludeStateOpt = Some(KeepToEmailStates.INACTIVE))
      for (kte <- zombieKtes) {
        debouncer.debounce(kte.emailAddress.address, 1 minute) { airbrake.notify(s"[KTE-STATE-MATCH] KTE ${kte.id.get} (keep ${kte.keepId} --- address ${kte.emailAddress}) is a zombie!") }
        kteCommander.deactivate(kte)
      }

      val zombieKtls = ktlRepo.getAllByKeepId(keepId, excludeStateOpt = Some(KeepToLibraryStates.INACTIVE))
      for (ktl <- zombieKtls) {
        debouncer.debounce(ktl.libraryId.toString, 1 minute) { airbrake.notify(s"[KTL-STATE-MATCH] KTL ${ktl.id.get} (keep ${ktl.keepId} --- lib ${ktl.libraryId}) is a zombie!") }
        ktlCommander.deactivate(ktl)
      }
    }
  }

  private def ensureLibrariesIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.getNoCache(keepId)
    val libraries = ktlRepo.getAllByKeepId(keepId).map(_.libraryId).toSet
    if (keep.recipients.libraries != libraries) {
      log.error(s"[KTL-MATCH] Keep $keepId's libraries don't match: ${keep.recipients.libraries} != $libraries")
      keepMutator.refreshLibraries(keepId)
    }
  }

  private def ensureParticipantsIntegrity(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.getNoCache(keepId)
    val users = ktuRepo.getAllByKeepId(keepId).map(_.userId).toSet
    if (keep.recipients.users != users) {
      log.error(s"[KTU-MATCH] Keep $keepId's participants don't match: ${keep.recipients.users} != $users")
      keepMutator.refreshParticipants(keepId)
    }
  }

  // Do this last
  private def ensureNoteAndTagsAreInSync(keepId: Id[Keep])(implicit session: RWSession) = {
    val keep = keepRepo.getNoCache(keepId)

    val tagsFromHashtags = Hashtags.findAllHashtagNames(keep.note.getOrElse("")).map(Hashtag.apply)
    val tagsFromCollections = collectionRepo.getHashtagsByKeepId(keep.id.get)
    if (tagsFromHashtags.map(_.normalized) != tagsFromCollections.map(_.normalized) && keep.isActive) {
      log.info(s"[NOTE-TAGS-MATCH] Keep $keepId's note does not match tags. $tagsFromHashtags vs $tagsFromCollections")
      autoFixKeepNoteAndTags(keep.id.get) // Async, max 1 thread system wide. i.e., this does not fix it immediately
    }
  }

  private val autoFixNoteLimiter = new ReactiveLock()
  def autoFixKeepNoteAndTags(keepId: Id[Keep]): Future[Unit] = {
    autoFixNoteLimiter.withLock {
      db.readWrite(attempts = 3) { implicit session =>
        val keep = keepRepo.getNoCache(keepId)
        val tagsFromNote = Hashtags.findAllHashtagNames(keep.note.getOrElse("")).map(Hashtag.apply)
        val allTagsOnKeep = tagCommander.getTagsForKeep(keep.id.get).toSet
        val diff = tagsFromNote.filter(nt => !allTagsOnKeep.exists(_.normalized == nt.normalized))
        if (keep.isActive && diff.nonEmpty) {
          val newNote = Hashtags.addHashtagsToString(keep.note.getOrElse(""), diff.toSeq)
          if (keep.note.getOrElse("").toLowerCase != newNote.toLowerCase) {
            log.info(s"[autoFixKeepNoteAndTags] (${keep.id.get}) Previous note: '${keep.note.getOrElse("")}', new: '$newNote'")
            Try(keepMutator.updateKeepNote(keep.userId.get, keep, newNote)).recover {
              case ex: Throwable =>
                log.warn(s"[autoFixKeepNoteAndTags] (${keep.id.get}) Couldn't update note", ex)
            }
          }
        }
      }
    }
  }
}
