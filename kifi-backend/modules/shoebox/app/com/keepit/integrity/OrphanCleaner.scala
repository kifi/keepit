package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.zookeeper.{ SequenceNumberCentralConfigKey, LongCentralConfigKey, CentralConfig }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.DBSession.RWSession

class OrphanCleaner @Inject() (
    val db: Database,
    changedURIRepo: ChangedURIRepo,
    val keepRepo: KeepRepo,
    val normUriRepo: NormalizedURIRepo,
    libraryRepo: LibraryRepo,
    centralConfig: CentralConfig,
    val airbrake: AirbrakeNotifier,
    uriIntegrityHelpers: UriIntegrityHelpers) extends UriIntegrityChecker {

  case class OrphanCleanerSequenceNumberKey[T](seqKey: String) extends SequenceNumberCentralConfigKey[T] {
    val longKey = new LongCentralConfigKey {
      def key: String = seqKey
      val namespace: String = "OrphanCleaner"
    }
  }

  val changedURISeqKey = OrphanCleanerSequenceNumberKey[ChangedURI]("ChangedURISeq")
  val bookmarkSeqKey = OrphanCleanerSequenceNumberKey[Keep]("BookmarkSeq")
  val normalizedURISeqKey = OrphanCleanerSequenceNumberKey[NormalizedURI]("NormalizedURISeq")

  private def getSequenceNumber[T](key: OrphanCleanerSequenceNumberKey[T]): SequenceNumber[T] = centralConfig(key) getOrElse (SequenceNumber.MinValue[T])

  private[this] val lock = new AnyRef

  def clean(readOnly: Boolean = true): Unit = lock.synchronized {
    cleanNormalizedURIsByChangedURIs(readOnly)
    cleanNormalizedURIsByBookmarks(readOnly)
  }

  def fullClean(readOnly: Boolean = true): Unit = lock.synchronized {
    cleanNormalizedURIsByNormalizedURIs(readOnly)
  }

  private[integrity] def cleanNormalizedURIsByChangedURIs(readOnly: Boolean = true): Unit = {
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var seq = getSequenceNumber(changedURISeqKey)
    var done = false

    log.info("start processing ChangedURIs")
    while (!done) {
      val changedURIs = db.readOnlyReplica { implicit s => changedURIRepo.getChangesSince(seq, 10) } // get applied changes
      done = changedURIs.isEmpty

      def collector(changedUri: ChangedURI, turnedActive: Boolean): Unit = {
        if (turnedActive) numUrisChangedToActive += 1
        numProcessed += 1
        seq = changedUri.seq
      }

      db.readWriteSeq(changedURIs, collector) { (s, changedUri) =>
        checkIntegrity(changedUri.oldUriId, readOnly)(s)
      }
      if (!done && !readOnly) centralConfig.update(changedURISeqKey, seq) // update high watermark
    }

    logProgress(seq.value, numProcessed, numUrisChangedToActive, readOnly)
  }

  private[integrity] def cleanNormalizedURIsByBookmarks(readOnly: Boolean = true): Unit = {
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var seq = getSequenceNumber(bookmarkSeqKey)
    var done = false

    log.info("start processing Bookmarks")
    while (!done) {
      val bookmarks = db.readOnlyReplica { implicit s => keepRepo.getBookmarksChanged(seq, 10) }
      done = bookmarks.isEmpty

      def collector(bookmark: Keep, turnedActive: Boolean): Unit = {
        if (turnedActive) numUrisChangedToActive += 1
        numProcessed += 1
        seq = bookmark.seq
      }

      db.readWriteSeq(bookmarks, collector) { (s, bookmark) =>
        implicit val session = s
        val hasUpdatedUri = bookmark.state match {
          case KeepStates.ACTIVE => checkIntegrity(bookmark.uriId, readOnly, hasKnownKeep = true)
          case KeepStates.INACTIVE => checkIntegrity(bookmark.uriId, readOnly)
        }

        val improvedBookmark = {
          val uri = normUriRepo.get(bookmark.uriId)
          uriIntegrityHelpers.improveKeepSafely(uri, bookmark)
        }
        hasUpdatedUri
      }
      if (!done && !readOnly) centralConfig.update(bookmarkSeqKey, seq) // update high watermark
    }

    logProgress(seq.value, numProcessed, numUrisChangedToActive, readOnly)
  }

  private[integrity] def cleanNormalizedURIsByNormalizedURIs(readOnly: Boolean = true): Unit = {
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var seq = getSequenceNumber(normalizedURISeqKey)
    var done = false

    log.info("start processing NormalizedURIs")
    while (!done) {
      val normalizedURIs = db.readOnlyReplica { implicit s => normUriRepo.getChanged(seq, Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_FAILED), 10) }
      done = normalizedURIs.isEmpty

      def collector(uri: NormalizedURI, turnedActive: Boolean): Unit = {
        if (turnedActive) numUrisChangedToActive += 1
        numProcessed += 1
        seq = uri.seq
      }

      db.readWriteSeq(normalizedURIs, collector) { (s, uri) =>
        checkIntegrity(uri.id.get, readOnly)(s)
      }
      if (numProcessed % 1000 == 0) {
        logProgress(seq.value, numProcessed, numUrisChangedToActive, readOnly)
      }

      if (!done && !readOnly) centralConfig.update(normalizedURISeqKey, seq) // update high watermark
    }

    logProgress(seq.value, numProcessed, numUrisChangedToActive, readOnly)
  }

  private def logProgress(seqValue: Long, numProcessed: Int, numUrisChangedToActive: Int, readOnly: Boolean): Unit = if (readOnly) {
    log.info(s"in progress: seq=${seqValue}, ${numProcessed} NormalizedURIs processed. Would have changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
  } else {
    log.info(s"in progress: seq=${seqValue}, ${numProcessed} NormalizedURIs processed. Changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
  }
}

trait UriIntegrityChecker extends Logging {
  val db: Database
  val normUriRepo: NormalizedURIRepo
  val keepRepo: KeepRepo

  protected def checkIntegrity(uriId: Id[NormalizedURI], readOnly: Boolean, hasKnownKeep: Boolean = false)(implicit session: RWSession): Boolean = {
    val currentUri = normUriRepo.get(uriId)
    val isActuallyKept = hasKnownKeep || keepRepo.exists(uriId)

    if (isActuallyKept) {
      // Make sure the uri is not inactive
      val newState = if (currentUri.state == NormalizedURIStates.INACTIVE) NormalizedURIStates.ACTIVE else currentUri.state
      val updatedUri = currentUri.withState(newState).withContentRequest(true)
      val isActuallyUpdated = currentUri != updatedUri
      if (!readOnly && isActuallyUpdated) normUriRepo.save(updatedUri)
      isActuallyUpdated
    } else false
  }
}
