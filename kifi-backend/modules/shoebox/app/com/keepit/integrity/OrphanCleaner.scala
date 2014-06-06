package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.commanders.KeepInterner
import com.keepit.common.zookeeper.{SequenceNumberCentralConfigKey, LongCentralConfigKey, CentralConfig}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.scraper.ScrapeSchedulerPlugin

class OrphanCleaner @Inject() (
  db: Database,
  changedURIRepo: ChangedURIRepo,
  renormalizedURLRepo: RenormalizedURLRepo,
  nuriRepo: NormalizedURIRepo,
  scrapeInfoRepo: ScrapeInfoRepo,
  scraper: ScrapeSchedulerPlugin,
  keepRepo: KeepRepo,
  bookmarkInterner: KeepInterner,
  centralConfig: CentralConfig,
  airbrake: AirbrakeNotifier
  ) extends Logging {

  implicit val dbMasterSlave = Database.Slave // use a slave for scanning part

  case class OrphanCleanerSequenceNumberKey[T](seqKey: String) extends SequenceNumberCentralConfigKey[T] {
    val longKey = new LongCentralConfigKey {
      def key: String = seqKey
      val namespace: String = "OrphanCleaner"
    }
  }

  val renormalizedURLSeqKey = OrphanCleanerSequenceNumberKey[RenormalizedURL]("RenormalizedURLSeq")
  val changedURISeqKey = OrphanCleanerSequenceNumberKey[ChangedURI]("ChangedURISeq")
  val bookmarkSeqKey = OrphanCleanerSequenceNumberKey[Keep]("BookmarkSeq")
  val normalizedURISeqKey = OrphanCleanerSequenceNumberKey[NormalizedURI]("NormalizedURISeq")

  private def getSequenceNumber[T](key: OrphanCleanerSequenceNumberKey[T]): SequenceNumber[T] = centralConfig(key) getOrElse(SequenceNumber.MinValue[T])

  private[this] val lock = new AnyRef

  def clean(readOnly: Boolean = true): Unit = lock.synchronized {
    cleanNormalizedURIsByRenormalizedURL(readOnly)
    cleanNormalizedURIsByChangedURIs(readOnly)
    cleanNormalizedURIsByBookmarks(readOnly)
  }

  def fullClean(readOnly: Boolean = true): Unit = lock.synchronized {
    cleanNormalizedURIsByNormalizedURIs(readOnly)
  }


  private def checkIntegrity(uriId: Id[NormalizedURI], readOnly: Boolean, hasKnownKeep: Boolean = false)(implicit session: RWSession): (Boolean, Boolean) = {
    val currentUri = nuriRepo.get(uriId)
    val activeScrapeInfoOption = scrapeInfoRepo.getActiveByUriId(uriId)
    val isActuallyKept = hasKnownKeep || keepRepo.exists(uriId)

    if (isActuallyKept) {
      // Make sure the uri is not inactive and has a scrape info
      val (updatedUri, turnedUriActive) = currentUri match {
        case uriToBeActive if uriToBeActive.state == NormalizedURIStates.INACTIVE || (activeScrapeInfoOption.isEmpty && !NormalizedURIStates.DO_NOT_SCRAPE.contains(uriToBeActive.state)) =>
          val update = if (readOnly) uriToBeActive else nuriRepo.save(uriToBeActive.withState(NormalizedURIStates.ACTIVE))
          (update, true)

        case _ => (currentUri, false)
      }

      // nuriRepo.save has side-effects on scrape_info && uri.state
      val createdScrapeInfo = scrapeInfoRepo.getActiveByUriId(updatedUri.id.get) match {
        case None if (!readOnly && !NormalizedURIStates.DO_NOT_SCRAPE.contains(updatedUri.state)) => {
          log.info(s"[checkIntegrity($uriId, $readOnly, $hasKnownKeep)] scheduling scrape for uri=${updatedUri.toShortString}")
          scraper.scheduleScrape(updatedUri)
          true
        }
        case _ => false
      }
      (turnedUriActive, createdScrapeInfo)

    } else {
      // Remove any existing scrape info and make the uri active
      val (updatedUri, turnedUriActive) = currentUri match {
        case scrapedUri if scrapedUri.state == NormalizedURIStates.SCRAPED || scrapedUri.state == NormalizedURIStates.SCRAPE_FAILED =>
          val update = if (readOnly) currentUri else nuriRepo.save(scrapedUri.withState(NormalizedURIStates.ACTIVE))
          (update, true)
        case uri => (currentUri, false)
      }

      scrapeInfoRepo.getActiveByUriId(updatedUri.id.get) match {
        case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.ACTIVE && !readOnly =>
          log.warn(s"[checkIntegrity($uriId, $readOnly, $hasKnownKeep)] mark scrapeInfo as INACTIVE: si=$scrapeInfo uri=${updatedUri.toShortString}")
          scrapeInfoRepo.save(scrapeInfo.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
        case _ => // all good
      }
      (turnedUriActive, false)
    }
  }


  private[integrity] def cleanNormalizedURIsByRenormalizedURL(readOnly: Boolean = true): Unit = {
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var numScrapeInfoCreated = 0
    var seq = getSequenceNumber(renormalizedURLSeqKey)
    var done = false

    log.info("start processing RenormalizedURL")
    while (!done) {
      val renormalizedURLs = db.readOnly{ implicit s => renormalizedURLRepo.getChangesSince(seq, 10) } // get applied changes
      done = renormalizedURLs.isEmpty

      def collector(renormalizedURL: RenormalizedURL, result: (Boolean, Boolean)): Unit = {
        val (turnedActive, fixedScrapeInfo) = result
        if (turnedActive) numUrisChangedToActive += 1
        if (fixedScrapeInfo) numScrapeInfoCreated += 1
        numProcessed += 1
        seq = renormalizedURL.seq
      }

      db.readWriteSeq(renormalizedURLs, collector){ (s, renormalizedURL) =>
        if (theTwo.contains(renormalizedURL.oldUriId.id)){
          log.info(s"orphan cleaner: processing renormalizedURL with old uri id ${renormalizedURL.oldUriId}")
        }
        checkIntegrity(renormalizedURL.oldUriId, readOnly)(s)
      }
      if (!done && !readOnly) centralConfig.update(renormalizedURLSeqKey, seq) // update high watermark
    }

    logProgress(seq.value, numProcessed, numUrisChangedToActive, numScrapeInfoCreated, readOnly)
  }

  private[integrity] def cleanNormalizedURIsByChangedURIs(readOnly: Boolean = true): Unit = {
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var numScrapeInfoCreated = 0
    var seq = getSequenceNumber(changedURISeqKey)
    var done = false

    log.info("start processing ChangedURIs")
    while (!done) {
      val changedURIs = db.readOnly{ implicit s => changedURIRepo.getChangesSince(seq, 10) } // get applied changes
      done = changedURIs.isEmpty

      def collector(changedUri: ChangedURI, result: (Boolean, Boolean)): Unit = {
        val (turnedActive, fixedScrapeInfo) = result
        if (turnedActive) numUrisChangedToActive += 1
        if (fixedScrapeInfo) numScrapeInfoCreated += 1
        numProcessed += 1
        seq = changedUri.seq
      }

      db.readWriteSeq(changedURIs, collector){ (s, changedUri) =>
        if (theTwo.contains(changedUri.oldUriId.id)){
          log.info(s"orphan cleaner: processing changedURIs with old uri id ${changedUri.oldUriId.id}")
        }
        checkIntegrity(changedUri.oldUriId, readOnly)(s)
      }
      if (!done && !readOnly) centralConfig.update(changedURISeqKey, seq) // update high watermark
    }

    logProgress(seq.value, numProcessed, numUrisChangedToActive, numScrapeInfoCreated, readOnly)
  }

  private[integrity] def cleanNormalizedURIsByBookmarks(readOnly: Boolean = true): Unit = {
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var numScrapeInfoCreated = 0
    var seq = getSequenceNumber(bookmarkSeqKey)
    var done = false

    log.info("start processing Bookmarks")
    while (!done) {
      val bookmarks = db.readOnly{ implicit s => keepRepo.getBookmarksChanged(seq, 10) }
      done = bookmarks.isEmpty

      def collector(bookmark: Keep, result: (Boolean, Boolean)): Unit = {
        val (turnedActive, fixedScrapeInfo) = result
        if (turnedActive) numUrisChangedToActive += 1
        if (fixedScrapeInfo) numScrapeInfoCreated += 1
        numProcessed += 1
        seq = bookmark.seq
      }

      db.readWriteSeq(bookmarks, collector){ (s, bookmark) =>
        bookmark.state match {
          case KeepStates.ACTIVE => checkIntegrity(bookmark.uriId, readOnly, hasKnownKeep = true)(s)
          case KeepStates.INACTIVE => checkIntegrity(bookmark.uriId, readOnly)(s)
          case KeepStates.DUPLICATE => checkIntegrity(bookmark.uriId, readOnly)(s)
        }
      }
      if (!done && !readOnly) centralConfig.update(bookmarkSeqKey, seq) // update high watermark
    }

    logProgress(seq.value, numProcessed, numUrisChangedToActive, numScrapeInfoCreated, readOnly)
  }


  private[integrity] def cleanNormalizedURIsByNormalizedURIs(readOnly: Boolean = true): Unit = {
    var numProcessed = 0
    var numUrisChangedToActive = 0
    var numScrapeInfoCreated = 0
    var seq = getSequenceNumber(normalizedURISeqKey)
    var done = false

    log.info("start processing NormalizedURIs")
    while (!done) {
      val normalizedURIs = db.readOnly{ implicit s => nuriRepo.getChanged(seq, Set(NormalizedURIStates.SCRAPED, NormalizedURIStates.SCRAPE_FAILED), 10) }
      done = normalizedURIs.isEmpty

      def collector(uri: NormalizedURI, result: (Boolean, Boolean)): Unit = {
        val (turnedActive, fixedScrapeInfo) = result
        if (turnedActive) numUrisChangedToActive += 1
        if (fixedScrapeInfo) numScrapeInfoCreated += 1
        numProcessed += 1
        seq = uri.seq
      }

      db.readWriteSeq(normalizedURIs, collector){ (s, uri) =>
        checkIntegrity(uri.id.get, readOnly)(s)
      }
      if (numProcessed % 1000 == 0) {
        logProgress(seq.value, numProcessed, numUrisChangedToActive, numScrapeInfoCreated, readOnly)
      }

      if (!done && !readOnly) centralConfig.update(normalizedURISeqKey, seq) // update high watermark
    }

    logProgress(seq.value, numProcessed, numUrisChangedToActive, numScrapeInfoCreated, readOnly)
  }

  private def logProgress(seqValue: Long, numProcessed: Int, numUrisChangedToActive: Int, numScrapeInfoCreated: Int, readOnly: Boolean): Unit = if (readOnly) {
    log.info(s"in progress: seq=${seqValue}, ${numProcessed} NormalizedURIs processed. Would have created ${numScrapeInfoCreated} ScrapeInfos and changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
  } else {
    log.info(s"in progress: seq=${seqValue}, ${numProcessed} NormalizedURIs processed. Created ${numScrapeInfoCreated} ScrapeInfos and changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
  }
}
