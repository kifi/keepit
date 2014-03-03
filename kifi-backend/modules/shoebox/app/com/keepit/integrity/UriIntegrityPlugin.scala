package com.keepit.integrity

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.actor.ActorInstance
import scala.concurrent.duration._
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import akka.pattern.{ask, pipe}
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.commanders.BookmarkInterner
import com.keepit.scraper.ScrapeSchedulerPlugin

trait UriChangeMessage

case class URIMigration(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) extends UriChangeMessage
case class URLMigration(url: URL, newUri: Id[NormalizedURI]) extends UriChangeMessage
case class BatchURIMigration(batchSize: Int)
case class BatchURLMigration(batchSize: Int)

class UriIntegrityActor @Inject()(
  db: Database,
  clock: Clock,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  bookmarkRepo: BookmarkRepo,
  bookmarkInterner: BookmarkInterner,
  deepLinkRepo: DeepLinkRepo,
  scrapeInfoRepo: ScrapeInfoRepo,
  scraper: ScrapeSchedulerPlugin,
  changedUriRepo: ChangedURIRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  collectionRepo: CollectionRepo,
  renormRepo: RenormalizedURLRepo,
  centralConfig: CentralConfig,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {

  private def getUserBookmarksByUrl(urlId: Id[URL])(implicit session: RSession): Map[Id[User], Seq[Bookmark]] = {
    bookmarkRepo.getByUrlId(urlId).groupBy(_.userId)
  }

  private def getUserBookmarksByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Map[Id[User], Seq[Bookmark]] = {
    bookmarkRepo.getByUri(uriId, excludeState = None).groupBy(_.userId)
  }

  /** tricky point: make sure (user, uri) pair is unique.  */
  private def handleBookmarks(oldUserBookmarks: Map[Id[User], Seq[Bookmark]], newUriId: Id[NormalizedURI])(implicit session: RWSession) = {

    val deactivatedBms = oldUserBookmarks.map{ case (userId, bms) =>
      val oldBm = bms.head
      bookmarkRepo.getByUriAndUserAllStates(newUriId, userId) match {
        case None => {
          log.info(s"going to redirect bookmark's uri: (userId, newUriId) = (${userId.id}, ${newUriId.id}), db or cache returns None")
          bookmarkRepo.deleteCache(oldBm)     // NOTE: we touch two different cache keys here and the following line
          bookmarkRepo.save(oldBm.withNormUriId(newUriId))
          Some(oldBm, None)
        }
        case Some(bm) =>
          if (oldBm.state == BookmarkStates.ACTIVE) {
            if (bm.state == BookmarkStates.INACTIVE) bookmarkRepo.save(bm.withActive(true))
            bookmarkRepo.save(oldBm.withActive(false))
            bookmarkRepo.deleteCache(oldBm)
            Some(oldBm, Some(bm))
          } else {
            None
          }
      }
    }

    val collectionsToUpdate = deactivatedBms.flatten.map {
      case (oldBm, None) => {
        keepToCollectionRepo.getCollectionsForBookmark(oldBm.id.get).toSet
      }
      case (oldBm, Some(newBm)) => {
        var collections = Set.empty[Id[Collection]]
        keepToCollectionRepo.getByBookmark(oldBm.id.get, excludeState = None).foreach { ktc =>
          collections += ktc.collectionId
          keepToCollectionRepo.getOpt(newBm.id.get, ktc.collectionId) match {
            case Some(newKtc) =>
              if (ktc.state == KeepToCollectionStates.ACTIVE && newKtc.state == KeepToCollectionStates.INACTIVE) {
                keepToCollectionRepo.save(newKtc.copy(state = KeepToCollectionStates.ACTIVE))
              }
              keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
            case None =>
              keepToCollectionRepo.save(ktc.copy(bookmarkId = newBm.id.get))
          }
        }
        collections
      }
    }.flatten

    collectionsToUpdate.foreach(collectionRepo.collectionChanged(_))
  }

  private def handleScrapeInfo(oldUri: NormalizedURI, newUri: NormalizedURI)(implicit session: RWSession) = {
    val (oldInfoOpt, newInfoOpt) = (scrapeInfoRepo.getByUriId(oldUri.id.get), scrapeInfoRepo.getByUriId(newUri.id.get))
    (oldInfoOpt, newInfoOpt) match {
      case (Some(oldInfo), None) if (oldInfo.state == ScrapeInfoStates.ACTIVE) => scraper.scheduleScrape(newUri)
      case (Some(oldInfo), Some(newInfo)) if (oldInfo.state == ScrapeInfoStates.ACTIVE && newInfo.state == ScrapeInfoStates.INACTIVE ) && (newUri.state != NormalizedURIStates.UNSCRAPABLE) => scraper.scheduleScrape(newUri)
      case _ =>
    }
  }


  /**
   * Any reference to the old uri should be redirected to the new one.
   * NOTE: We have 1-1 mapping from entities to url, the only exception (as of writing) is normalizedUri-url mapping, which is 1 to n.
   * A migration from uriA to uriB is (almost) equivalent to N url to uriB migrations, where the N urls are currently associated with uriA.
   */
  private def handleURIMigration(change: ChangedURI)(implicit session: RWSession): Unit = {
    val (oldUriId, newUriId) = (change.oldUriId, change.newUriId)
    if (oldUriId == newUriId || change.state != ChangedURIStates.ACTIVE) {
      if (oldUriId == newUriId) changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.INACTIVE)))
    } else {
      val oldUri = uriRepo.get(oldUriId)
      uriRepo.get(newUriId) match {
        case uri if uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.REDIRECTED => uriRepo.save(uri.copy(state = NormalizedURIStates.ACTIVE, redirect = None, redirectTime = None))
        case _ =>
      }

      val urls = urlRepo.getByNormUri(oldUriId)
      urls.foreach{ url =>
        handleURLMigrationNoBookmarks(url, newUriId)
      }

      uriRepo.getByRedirection(oldUri.id.get).foreach{ uri =>
        uriRepo.save(uri.withRedirect(newUriId, currentDateTime))
      }
      uriRepo.save(oldUri.withRedirect(newUriId, currentDateTime))

      // retrieve bms by uri is more robust than by url (against cache bugs), in case bm and its url are pointing to different uris
      val bms = getUserBookmarksByUri(oldUriId)
      handleBookmarks(bms, newUriId)

      changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.APPLIED)))
    }
  }

  /**
   * url now pointing to a new uri, any entity related to that url should update its uri reference.
   */
  private def handleURLMigration(url: URL, newUriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    handleURLMigrationNoBookmarks(url, newUriId)
    val bms = getUserBookmarksByUrl(url.id.get)
    handleBookmarks(bms, newUriId)
  }

  private def handleURLMigrationNoBookmarks(url: URL, newUriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
      log.info(s"migrating url ${url.id} to new uri: ${newUriId}")

      val oldUriId = url.normalizedUriId
      urlRepo.save(url.withNormUriId(newUriId).withHistory(URLHistory(clock.now, oldUriId, URLHistoryCause.MIGRATED)))
      val (oldUri, newUri) = (uriRepo.get(url.normalizedUriId), uriRepo.get(newUriId))
      if (newUri.redirect.isDefined) uriRepo.save(newUri.copy(redirect = None, redirectTime = None).withState(NormalizedURIStates.ACTIVE))

      handleScrapeInfo(oldUri, newUri)

      deepLinkRepo.getByUrl(url.id.get).map{ link =>
        deepLinkRepo.save(link.withNormUriId(newUriId))
      }
  }

  private def batchURIMigration(batchSize: Int): Int = {
    val toMerge = getOverDueList(batchSize)
    log.info(s"batch merge uris: ${toMerge.size} pairs of uris to be merged")

    toMerge.map{ change =>
      try{
        db.readWrite{ implicit s => handleURIMigration(change) }
      } catch {
        case e: Exception => {
          airbrake.notify(s"Exception in migrating uri ${change.oldUriId} to ${change.newUriId}. Going to delete them from cache",e)
          db.readWrite{ implicit s => changedUriRepo.save((change.withState(ChangedURIStates.ACTIVE)))}   // bump up seqNum. Will be retried.

          try{
            db.readOnly{ implicit s => List(uriRepo.get(change.oldUriId), uriRepo.get(change.newUriId)) foreach {uriRepo.deleteCache} }
          } catch{
            case e: Exception => airbrake.notify(s"error in getting uri ${change.oldUriId} or ${change.newUriId} from db by id.")
          }
        }
      }
    }

    toMerge.sortBy(_.seq).lastOption.map{ x => centralConfig.update(URIMigrationSeqNumKey, x.seq) }
    log.info(s"batch merge uris completed in database: ${toMerge.size} pairs of uris merged. zookeeper seqNum updated.")
    toMerge.size
  }

  private def getOverDueList(fetchSize: Int = -1) = {
    val lowSeq = centralConfig(URIMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
    db.readOnly{ implicit s => changedUriRepo.getChangesSince(lowSeq, fetchSize, state = ChangedURIStates.ACTIVE)}
  }

  private def batchURLMigration(batchSize: Int) = {
    val toMigrate = getOverDueURLMigrations(batchSize)
    log.info(s"${toMigrate.size} urls need renormalization")

    toMigrate.foreach{ renormURL =>
      try{
        db.readWrite{ implicit s =>
          val url = urlRepo.get(renormURL.urlId)
          handleURLMigration(url, renormURL.newUriId)
          renormRepo.saveWithoutIncreSeqnum(renormURL.withState(RenormalizedURLStates.APPLIED))
        }
      } catch {
        case e: Exception =>
          airbrake.notify(e)
          db.readWrite{ implicit s => renormRepo.saveWithoutIncreSeqnum(renormURL.withState(RenormalizedURLStates.INACTIVE)) }
      }
    }

    toMigrate.sortBy(_.seq).lastOption.map{ x => centralConfig.update(URLMigrationSeqNumKey, x.seq)}
    log.info(s"${toMigrate.size} urls renormalized.")
  }

  private def getOverDueURLMigrations(fetchSize: Int = -1) = {
    val lowSeq = centralConfig(URLMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
    db.readOnly{ implicit s => renormRepo.getChangesSince(lowSeq, fetchSize, state = RenormalizedURLStates.ACTIVE)}
  }


  def receive = {
    case BatchURIMigration(batchSize) => Future.successful(batchURIMigration(batchSize)) pipeTo sender
    case URIMigration(oldUri, newUri) => db.readWrite{ implicit s => changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUri)) }   // process later
    case URLMigration(url, newUri) => db.readWrite{ implicit s => handleURLMigration(url, newUri)}
    case BatchURLMigration(batchSize) => batchURLMigration(batchSize)
    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends SchedulerPlugin  {
  def handleChangedUri(change: UriChangeMessage): Unit
  def batchURIMigration(batchSize: Int = -1): Future[Int]
  def batchURLMigration(batchSize: Int = -1): Unit
}

@Singleton
class UriIntegrityPluginImpl @Inject() (
  actor: ActorInstance[UriIntegrityActor],
  val scheduling: SchedulingProperties
) extends UriIntegrityPlugin with Logging {
  override def enabled = true
  override def onStart() {
     log.info("starting UriIntegrityPluginImpl")
     scheduleTaskOnLeader(actor.system, 1 minutes, 45 seconds, actor.ref, BatchURIMigration(50))
     scheduleTaskOnLeader(actor.system, 1 minutes, 60 seconds, actor.ref, BatchURLMigration(100))
  }
  override def onStop() {
     log.info("stopping UriIntegrityPluginImpl")
  }

  def handleChangedUri(change: UriChangeMessage) = {
    actor.ref ! change
  }

  def batchURIMigration(batchSize: Int) = actor.ref.ask(BatchURIMigration(batchSize))(1 minute).mapTo[Int]
  def batchURLMigration(batchSize: Int) = actor.ref ! BatchURLMigration(batchSize)

}
