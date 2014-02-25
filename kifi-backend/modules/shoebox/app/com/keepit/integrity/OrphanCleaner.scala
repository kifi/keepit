package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.commanders.BookmarkInterner
import com.keepit.common.zookeeper.{LongCentralConfigKey, CentralConfig}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}

class OrphanCleaner @Inject() (
  db: Database,
  changedURIRepo: ChangedURIRepo,
  renormalizedURLRepo: RenormalizedURLRepo,
  nuriRepo: NormalizedURIRepo,
  bookmarkRepo: BookmarkRepo,
  bookmarkInterner: BookmarkInterner,
  centralConfig: CentralConfig,
  airbrake: AirbrakeNotifier
  ) extends Logging {

  implicit val dbMasterSlave = Database.Slave // use a slave for scanning part

  class ConfigKey(override val key: String) extends LongCentralConfigKey {
    override val namespace = "OrphanCleaner"
  }

  private def getSequenceNumber(key: ConfigKey): SequenceNumber = centralConfig(key).map(SequenceNumber(_)).getOrElse(SequenceNumber.MinValue)

  private[this] val lock = new AnyRef

  def clean(readOnly: Boolean = true): Unit = lock.synchronized {
    cleanNormalizedURIsByRenormalizedURL(readOnly)
    cleanNormalizedURIsByChangedURIs(readOnly)
    cleanNormalizedURIsByBookmarks(readOnly)
  }

  def fullClean(readOnly: Boolean = true): Unit = lock.synchronized {
    cleanNormalizedURIsByNormalizedURIs(readOnly)
  }

  private def getNormalizedURI(id: Id[NormalizedURI])(implicit session: RSession): Option[NormalizedURI] = {
    try { Some(nuriRepo.get(id)) } catch { case e: Throwable => None }
  }

  private def tryMakeActive(id: Id[NormalizedURI], readOnly: Boolean)(implicit session: RWSession): Boolean = {
    getNormalizedURI(id) match {
      case Some(uri) => return tryMakeActive(uri, readOnly)
      case None => false
    }
  }

  private def tryMakeActive(uri: NormalizedURI, readOnly: Boolean)(implicit session: RWSession): Boolean = {
    if (uri.state == NormalizedURIStates.SCRAPED || uri.state == NormalizedURIStates.SCRAPE_FAILED) {
      if (!bookmarkRepo.exists(uri.id.get)) {
        if (!readOnly) {
          nuriRepo.save(uri.withState(NormalizedURIStates.ACTIVE)) // this will fix ScrapeInfo as well
        }
        return true
      }
    }
    false
  }

  private[integrity] def cleanNormalizedURIsByRenormalizedURL(readOnly: Boolean = true): Unit = {
    val renormalizedURLSeqKey = new ConfigKey("RenormalizedURLSeq")
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var seq = getSequenceNumber(renormalizedURLSeqKey)
    var done = false

    log.info("start processing RenormalizedURL")
    while (!done) {
      val renormalizedURLs = db.readOnly{ implicit s => renormalizedURLRepo.getChangesSince(seq, 100) } // get applied changes
      done = renormalizedURLs.isEmpty

      db.readWrite{ implicit s =>
        renormalizedURLs.foreach{ renormalizedURL =>
          if (tryMakeActive(renormalizedURL.oldUriId, readOnly)) numUrisChangedToActive += 1
          numProcessed += 1
          seq = renormalizedURL.seq
        }
      }
      if (!done && !readOnly) centralConfig.update(renormalizedURLSeqKey, seq.value) // update high watermark
    }

    if (readOnly) {
      log.info(s"seq=${seq.value}, ${numProcessed} RenormalizedURL processed. Would have changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    } else {
      log.info(s"seq=${seq.value}, ${numProcessed} RenormalizedURL processed. Changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    }
  }

  private[integrity] def cleanNormalizedURIsByChangedURIs(readOnly: Boolean = true): Unit = {
    val changedURISeqKey = new ConfigKey("ChangedURISeq")
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var seq = getSequenceNumber(changedURISeqKey)
    var done = false

    log.info("start processing ChangedURIs")
    while (!done) {
      val changedURIs = db.readOnly{ implicit s => changedURIRepo.getChangesSince(seq, 100) } // get applied changes
      done = changedURIs.isEmpty

      db.readWrite{ implicit s =>
        changedURIs.foreach{ changedUri =>
          if (tryMakeActive(changedUri.oldUriId, readOnly)) numUrisChangedToActive += 1
          numProcessed += 1
          seq = changedUri.seq
        }
      }
      if (!done && !readOnly) centralConfig.update(changedURISeqKey, seq.value) // update high watermark
    }

    if (readOnly) {
      log.info(s"seq=${seq.value}, ${numProcessed} ChangedURIs processed. Would have changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    } else {
      log.info(s"seq=${seq.value}, ${numProcessed} ChangedURIs processed. Changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    }
  }

  private[integrity] def cleanNormalizedURIsByBookmarks(readOnly: Boolean = true): Unit = {
    val bookmarkSeqKey = new ConfigKey("BookmarkSeq")
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var numUrisChangedToScrapeWanted = 0
    var seq = getSequenceNumber(bookmarkSeqKey)
    var done = false

    log.info("start processing Bookmarks")
    while (!done) {
      val bookmarks = db.readOnly{ implicit s => bookmarkRepo.getBookmarksChanged(seq, 100) }
      done = bookmarks.isEmpty

      db.readWrite{ implicit s =>
        bookmarks.foreach{ bookmark =>
          val uri = getNormalizedURI(bookmark.uriId)
          if (!uri.isDefined) airbrake.notify("error getting NormalizedURI in OrphanCleaner")

          uri.foreach{ u =>
            bookmark.state match {
              case BookmarkStates.ACTIVE =>
                if (u.state == NormalizedURIStates.ACTIVE || u.state == NormalizedURIStates.INACTIVE) {
                  numUrisChangedToScrapeWanted += 1
                  if (!readOnly) {
                    bookmarkInterner.internUri(u.withState(NormalizedURIStates.SCRAPE_WANTED))
                  }
                }
              case BookmarkStates.INACTIVE =>
                if (tryMakeActive(u, readOnly)) numUrisChangedToActive += 1
            }
          }
          numProcessed += 1
          seq = bookmark.seq
        }
      }
      if (!done && !readOnly) centralConfig.update(bookmarkSeqKey, seq.value) // update high watermark
    }

    if (readOnly) {
      log.info(s"seq=${seq.value}, ${numProcessed} Bookmarks processed. Would have changed ${numUrisChangedToScrapeWanted} NormalizedURIs to SCRAPE_WANTED, ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    } else {
      log.info(s"seq=${seq.value}, ${numProcessed} Bookmarks processed. Changed ${numUrisChangedToScrapeWanted} NormalizedURIs to SCRAPE_WANTED, ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    }
  }


  private[integrity] def cleanNormalizedURIsByNormalizedURIs(readOnly: Boolean = true): Unit = {
    val normalizedURISeqKey = new ConfigKey("NormalizedURISeq")
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var seq = getSequenceNumber(normalizedURISeqKey)
    var done = false

    log.info("start processing NormalizedURIs")
    while (!done) {
      val normalizedURIs = db.readOnly{ implicit s => nuriRepo.getChanged(seq, Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_FAILED), 100) }
      done = normalizedURIs.isEmpty

      db.readWrite{ implicit s =>
        normalizedURIs.foreach{ uri =>
          if (tryMakeActive(uri.id.get, readOnly)) numUrisChangedToActive += 1
          numProcessed += 1
          seq = uri.seq
        }
      }
      if (numProcessed % 1000 == 0) {
        if (readOnly) {
          log.info(s"in progress: seq=${seq.value}, ${numProcessed} NormalizedURIs processed. Would have changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
        } else {
          log.info(s"in progress: seq=${seq.value}, ${numProcessed} NormalizedURIs processed. Changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
        }
      }

      if (!done && !readOnly) centralConfig.update(normalizedURISeqKey, seq.value) // update high watermark
    }

    if (readOnly) {
      log.info(s"seq=${seq.value}, ${numProcessed} NormalizedURIs processed. Would have changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    } else {
      log.info(s"seq=${seq.value}, ${numProcessed} NormalizedURIs processed. Changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    }
  }
}