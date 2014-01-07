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

@Singleton
class OrphanCleaner @Inject() (
    db: Database,
    changedURIRepo: ChangedURIRepo,
    nuriRepo: NormalizedURIRepo,
    bookmarkRepo: BookmarkRepo,
    airbrake: AirbrakeNotifier
) extends Logging {
  log.info("created")

  private[this] var changedUriSequenceNumber = SequenceNumber.MinValue
  private[this] var bookmarkSequenceNumber = SequenceNumber.MinValue
  private[this] val lock = new AnyRef

  def cleanNormalizedURIs(readOnly: Boolean = true): Unit = lock.synchronized {
    cleanNormalizedURIsByChangedURIs(readOnly)
    cleanNormalizedURIsByBookmarks(readOnly)
  }

  private def getNormalizedURI(id: Id[NormalizedURI])(implicit session: RSession): Option[NormalizedURI] = {
    try { Some(nuriRepo.get(id)) } catch { case e: Throwable => None }
  }

  private[integrity] def cleanNormalizedURIsByChangedURIs(readOnly: Boolean = true): Unit = {
    var numChangedUrisProcessed = 0
    var numUrisChangedToActive = 0

    var seq = changedUriSequenceNumber
    var done = false
    log.info("start processing ChangedURIs")
    while (!done) {
      db.readWrite{ implicit s =>
        val changedURIs = changedURIRepo.getChangesSince(seq, 100) // get applied changes
        done = changedURIs.isEmpty
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
          numChangedUrisProcessed += 1
          seq = changedUri.seq
          if (!readOnly) changedUriSequenceNumber = seq // update high watermark
        }
      }
    }

    if (readOnly) {
      log.info(s"${numChangedUrisProcessed} ChangedURIs processed. Would have changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    } else {
      log.info(s"${numChangedUrisProcessed} ChangedURIs processed. Changed ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    }
  }

  private[integrity] def cleanNormalizedURIsByBookmarks(readOnly: Boolean = true): Unit = {
    var numBookmarksProcessed = 0
    var numUrisChangedToActive = 0
    var numUrisChangedToScrapeWanted = 0

    var seq = bookmarkSequenceNumber
    var done = false
    log.info("start processing Bookmarks")
    while (!done) {
      db.readWrite{ implicit s =>
        val bookmarks = bookmarkRepo.getBookmarksChanged(seq, 100)
        done = bookmarks.isEmpty
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
                if (u.state != NormalizedURIStates.ACTIVE && u.state != NormalizedURIStates.INACTIVE) {
                  if (!bookmarkRepo.exists(u.id.get)) {
                    numUrisChangedToActive += 1
                    if (!readOnly) {
                      nuriRepo.save(u.withState(NormalizedURIStates.ACTIVE)) // this will fix ScrapeInfo as well
                    }
                  }
                }
            }
          }
          numBookmarksProcessed += 1
          seq = bookmark.seq
          if (!readOnly) bookmarkSequenceNumber = seq // update high watermark
        }
      }
    }

    if (readOnly) {
      log.info(s"${numBookmarksProcessed} Bookmarks processed. Would have changed ${numUrisChangedToScrapeWanted} NormalizedURIs to SCRAPE_WANTED, ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    } else {
      log.info(s"${numBookmarksProcessed} Bookmarks processed. Changed ${numUrisChangedToScrapeWanted} NormalizedURIs to SCRAPE_WANTED, ${numUrisChangedToActive} NormalizedURIs to ACTIVE.")
    }
  }
}

