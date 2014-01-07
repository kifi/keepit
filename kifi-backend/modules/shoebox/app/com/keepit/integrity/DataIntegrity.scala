package com.keepit.integrity

import com.keepit.common.logging.Logging
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.model._
import akka.actor.{Actor, Cancellable, Props, ActorSystem}
import scala.concurrent.duration._
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.zookeeper.LongCentralConfigKey


trait DataIntegrityPlugin extends SchedulingPlugin

class DataIntegrityPluginImpl @Inject() (
    actor: ActorInstance[DataIntegrityActor],
    val schedulingProperties: SchedulingProperties) //only on leader
  extends Logging with DataIntegrityPlugin {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actor.system, 5 minutes, 1 hour, actor.ref, Cron)
  }
}

private[integrity] case object CleanOrphans
private[integrity] case object Cron

private[integrity] class DataIntegrityActor @Inject() (
    airbrake: AirbrakeNotifier,
    orphanCleaner: OrphanCleaner)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case CleanOrphans =>
      // This cleans up cases when we have a normalizedUri, but no Url. This *only* happens when we renormalize, so does not need to happen every night.
      //orphanCleaner.cleanNormalizedURIs(false)
    case Cron =>
      if (currentDateTime.hourOfDay().get() == 21) // 9pm PST
        self ! CleanOrphans
    case m => throw new UnsupportedActorMessage(m)
  }
}

class OrphanCleaner @Inject() (
    db: Database,
    changedURIRepo: ChangedURIRepo,
    renormalizedURLRepo: RenormalizedURLRepo,
    nuriRepo: NormalizedURIRepo,
    bookmarkRepo: BookmarkRepo,
    centralConfig: CentralConfig,
    airbrake: AirbrakeNotifier
) extends Logging {

  implicit val dbMasterSlave = Database.Slave // use a slave for scanning part

  class ConfigKey(override val key: String) extends LongCentralConfigKey {
    override val namespace = "OrphanCleaner"
  }

  private[this] val renormalizedURLSeqKey = new ConfigKey("RenormalizedURLSeq")
  private[this] val changedURISeqKey = new ConfigKey("ChangedURISeq")
  private[this] val bookmarkSeqKey = new ConfigKey("BookmarkSeq")
  private[this] val normalizedURISeqKey = new ConfigKey("NormalizedURISeq")

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

  private[integrity] def cleanNormalizedURIsByRenormalizedURL(readOnly: Boolean = true): Unit = {
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
          getNormalizedURI(renormalizedURL.oldUriId).map{ uri =>
            if (uri.state == NormalizedURIStates.SCRAPED || uri.state == NormalizedURIStates.SCRAPE_FAILED) {
              if (!bookmarkRepo.exists(uri.id.get)) {
                numUrisChangedToActive += 1
                if (!readOnly) {
                  nuriRepo.save(uri.withState(NormalizedURIStates.ACTIVE)) // this will fix ScrapeInfo as well
                }
              }
            }
          }
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
          getNormalizedURI(changedUri.oldUriId).map{ uri =>
            if (uri.state == NormalizedURIStates.SCRAPED || uri.state == NormalizedURIStates.SCRAPE_FAILED) {
              if (!bookmarkRepo.exists(uri.id.get)) {
                numUrisChangedToActive += 1
                if (!readOnly) {
                  nuriRepo.save(uri.withState(NormalizedURIStates.ACTIVE)) // this will fix ScrapeInfo as well
                }
              }
            }
          }
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
                    nuriRepo.save(u.withState(NormalizedURIStates.SCRAPE_WANTED)) // this will fix ScrapeInfo as well
                  }
                }
              case BookmarkStates.INACTIVE =>
                if (u.state != NormalizedURIStates.ACTIVE && u.state != NormalizedURIStates.INACTIVE &&  u.state != NormalizedURIStates.REDIRECTED) {
                  if (!bookmarkRepo.exists(u.id.get)) {
                    numUrisChangedToActive += 1
                    if (!readOnly) {
                      nuriRepo.save(u.withState(NormalizedURIStates.ACTIVE)) // this will fix ScrapeInfo as well
                    }
                  }
                }
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
          if (uri.state == NormalizedURIStates.SCRAPED || uri.state == NormalizedURIStates.SCRAPE_FAILED) {
            if (!bookmarkRepo.exists(uri.id.get)) {
              numUrisChangedToActive += 1
              if (!readOnly) {
                nuriRepo.save(uri.withState(NormalizedURIStates.ACTIVE)) // this will fix ScrapeInfo as well
              }
            }
          }
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

