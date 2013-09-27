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
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.db.slick.DBSession.RWSession
import akka.pattern.{ask, pipe}
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._

trait UriChangeMessage

case class MergedUri(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) extends UriChangeMessage
case class URLMigration(url: URL, newUri: Id[NormalizedURI]) extends UriChangeMessage
case class BatchUpdateMerge(batchSize: Int)
case class BatchURLMigration(batchSize: Int)

class UriIntegrityActor @Inject()(
  db: Database,
  clock: Clock,
  urlHashCache: NormalizedURIUrlHashCache,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  collectionRepo: CollectionRepo,
  commentReadRepo: CommentReadRepo,
  deepLinkRepo: DeepLinkRepo,
  followRepo: FollowRepo,
  scrapeInfoRepo: ScrapeInfoRepo,
  changedUriRepo: ChangedURIRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  renormRepo: RenormalizedURLRepo,
  centralConfig: CentralConfig,
  airbrake: AirbrakeNotifier,
  scraper: ScraperPlugin
) extends FortyTwoActor(airbrake) with Logging {

  /** tricky point: make sure (user, uri) pair is unique.  */
  private def handleBookmarks(oldUserBookmarks: Map[Id[User], Seq[Bookmark]], newUriId: Id[NormalizedURI])(implicit session: RWSession) = {
    val deactivatedBms = oldUserBookmarks.map{ case (userId, bms) =>
      val oldBm = bms.head
      bookmarkRepo.getByUriAndUser(newUriId, userId, excludeState = None) match {
        case None => {
          log.info(s"going to redirect bookmark's uri: (userId, newUriId) = (${userId.id}, ${newUriId.id}), db or cache returns None")
          bookmarkRepo.removeFromCache(oldBm)     // NOTE: we touch two different cache keys here and the following line
          bookmarkRepo.save(oldBm.withNormUriId(newUriId)); None
        }
        case Some(bm) => if (oldBm.state == BookmarkStates.ACTIVE) {
          if (bm.state == BookmarkStates.INACTIVE) bookmarkRepo.save(bm.withActive(true))
          bookmarkRepo.save(oldBm.withActive(false));
          bookmarkRepo.removeFromCache(oldBm); Some(oldBm, bm)
        } else None
      }
    }

    deactivatedBms.flatten.map {
      case (oldBm, newBm) => {
        val co1 = keepToCollectionRepo.getCollectionsForBookmark(oldBm.id.get).toSet
        val co2 = keepToCollectionRepo.getCollectionsForBookmark(newBm.id.get).toSet
        val inter = co1 & co2
        val diff = co1 -- co2
        keepToCollectionRepo.getByBookmark(oldBm.id.get, excludeState = None).foreach { ktc =>
          if (inter.contains(ktc.collectionId)) keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
          if (diff.contains(ktc.collectionId)) keepToCollectionRepo.save(ktc.copy(bookmarkId = newBm.id.get))
        }
      }
    }
  }

  /**
   * Any reference to the old uri should be redirected to the new one.
   * NOTE: We have 1-1 mapping from entities to url, the only exception (as of writing) is normalizedUri-url mapping, which is 1 to n.
   * A migration from uriA to uriB is (almost) equivalent to N url to uriB migrations, where the N urls are currently associated with uriA.
   */
  private def processMerge(change: ChangedURI)(implicit session: RWSession): Seq[Option[NormalizedURI]] = {
    val (oldUriId, newUriId) = (change.oldUriId, change.newUriId)
    if (oldUriId == newUriId || change.state != ChangedURIStates.ACTIVE) {
      if (oldUriId == newUriId) changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.INACTIVE)))
      Nil
    } else {
      val oldUri = uriRepo.get(oldUriId)
      val newUri = uriRepo.get(newUriId) match {
        case uri if uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.REDIRECTED => uriRepo.save(uri.copy(state = NormalizedURIStates.ACTIVE, redirect = None, redirectTime = None))
        case uri => uri
      }

      val toBeScraped = urlRepo.getByNormUri(oldUriId).map{ url =>
        handleURLMigration(url, newUriId, delayScrape = true)
      }

      uriRepo.getByRedirection(oldUri.id.get).foreach{ uri =>
        uriRepo.save(uri.withRedirect(newUriId, currentDateTime))
      }  
      uriRepo.save(oldUri.withRedirect(newUriId, currentDateTime))

      val saved = changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.APPLIED)))
      
      toBeScraped
    }
  }

  /**
   * url now pointing to a new uri, any entity related to that url should update its uri reference.
   */
  private def handleURLMigration(url: URL, newUriId: Id[NormalizedURI], delayScrape: Boolean = false)(implicit session: RWSession): Option[NormalizedURI] = {
      log.info(s"migrating url ${url.id} to new uri: ${newUriId}")
      urlRepo.save(url.withNormUriId(newUriId).withHistory(URLHistory(clock.now, newUriId, URLHistoryCause.MIGRATED)))
      val (oldUri, newUri) = (uriRepo.get(url.normalizedUriId), uriRepo.get(newUriId))
      if (newUri.redirect.isDefined) uriRepo.save(newUri.copy(redirect = None, redirectTime = None))
      val toBeScraped = if (oldUri.state != NormalizedURIStates.ACTIVE && oldUri.state != NormalizedURIStates.INACTIVE && (newUri.state == NormalizedURIStates.ACTIVE || newUri.state == NormalizedURIStates.INACTIVE)) {
        Some(uriRepo.save(uriRepo.get(newUriId).withState(NormalizedURIStates.SCRAPE_WANTED)))
      } else None

      val oldUserBms = bookmarkRepo.getByUrlId(url.id.get).groupBy(_.userId)
      handleBookmarks(oldUserBms, newUriId)

      deepLinkRepo.getByUrl(url.id.get).map{ link =>
        deepLinkRepo.save(link.withNormUriId(newUriId))
      }

      followRepo.getByUrl(url.id.get, excludeState = None).map{ follow =>
        followRepo.save(follow.withNormUriId(newUriId))
      }
      if (!delayScrape) { toBeScraped.map{scraper.asyncScrape(_)}; None }
      else toBeScraped
  }

  private def batchUpdateMerge(batchSize: Int): Int = {
    val toMerge = getOverDueList(batchSize)
    log.info(s"batch merge uris: ${toMerge.size} pairs of uris to be merged")
    val toScrape = db.readWrite{ implicit s =>
      toMerge.map{ change => 
        try{
          processMerge(change)
        } catch {
          case e: Exception => {
            changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.INACTIVE)))
            Nil
          }
        }
      }
    }
    toMerge.sortBy(_.seq).lastOption.map{ x => centralConfig.update(new ChangedUriSeqNumKey(), x.seq.value) }
    log.info(s"batch merge uris completed in database: ${toMerge.size} pairs of uris merged. zookeeper seqNum updated.")
    
    val uniqueToScrape = toScrape.flatten.filter(_.isDefined).groupBy(_.get.url).mapValues(_.head).values
    log.info(s"start scraping ${uniqueToScrape.size} pages")
    uniqueToScrape.map{ x => scraper.asyncScrape(x.get)}
    toMerge.size
  }

  private def getOverDueList(fetchSize: Int = -1) = {
    centralConfig(new ChangedUriSeqNumKey()) match {
      case None => db.readOnly{ implicit s => changedUriRepo.getChangesSince(SequenceNumber(0), fetchSize, state = ChangedURIStates.ACTIVE)}
      case Some(seqNum) => db.readOnly{ implicit s => changedUriRepo.getChangesSince(SequenceNumber(seqNum), fetchSize, state = ChangedURIStates.ACTIVE)}
    }
  }
  
  private def batchURLMigration(batchSize: Int) = {
    val toMigrate = getOverDueURLMigrations(batchSize)
    log.info(s"${toMigrate.size} urls need renormalization")
    
    val toScrapes = db.readWrite{ implicit s => 
      toMigrate.map{ renormURL =>
        val url = urlRepo.get(renormURL.urlId)
        val toScrape = handleURLMigration(url, renormURL.newUriId, delayScrape = true)
        renormRepo.saveWithoutIncreSeqnum(renormURL.withState(RenormalizedURLStates.APPLIED))
        toScrape
      }
    }
    toMigrate.sortBy(_.seq).lastOption.map{ x => centralConfig.update(new URLMigrationSeqNumKey(), x.seq.value)}
    val uniqueToScrape = toScrapes.filter(_.isDefined).groupBy(_.get.url).mapValues(_.head).values
    
    log.info(s"${toMigrate.size} urls renormalized. start scraping ${uniqueToScrape.size} pages")
    uniqueToScrape.foreach{ x => scraper.asyncScrape(x.get)}
  }
  
  private def getOverDueURLMigrations(fetchSize: Int = -1) = {
    val lowSeq = centralConfig(new URLMigrationSeqNumKey()) match {
      case None => SequenceNumber(0)
      case Some(seqNum) => SequenceNumber(seqNum)
    }
    db.readOnly{ implicit s => renormRepo.getChangesSince(lowSeq, fetchSize, state = RenormalizedURLStates.ACTIVE)}
  }
  

  def receive = {
    case BatchUpdateMerge(batchSize) => Future.successful(batchUpdateMerge(batchSize)) pipeTo sender
    case MergedUri(oldUri, newUri) => db.readWrite{ implicit s => changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUri)) }   // process later
    case URLMigration(url, newUri) => db.readWrite{ implicit s => handleURLMigration(url, newUri)}
    case BatchURLMigration(batchSize) => batchURLMigration(batchSize)
    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends SchedulingPlugin  {
  def handleChangedUri(change: UriChangeMessage): Unit
  def batchUpdateMerge(batchSize: Int = -1): Future[Int]
  def batchURLMigration(batchSize: Int = -1): Unit
}

@Singleton
class UriIntegrityPluginImpl @Inject() (
  actor: ActorInstance[UriIntegrityActor],
  val schedulingProperties: SchedulingProperties
) extends UriIntegrityPlugin with Logging {
  override def enabled = true
  override def onStart() {
     log.info("starting UriIntegrityPluginImpl")
     scheduleTask(actor.system, 1 minutes, 45 seconds, actor.ref, BatchUpdateMerge(50))
     scheduleTask(actor.system, 1 minutes, 60 seconds, actor.ref, BatchURLMigration(100))
  }
  override def onStop() {
     log.info("stopping UriIntegrityPluginImpl")
  }

  def handleChangedUri(change: UriChangeMessage) = {
    actor.ref ! change
  }

  def batchUpdateMerge(batchSize: Int) = actor.ref.ask(BatchUpdateMerge(batchSize))(1 minute).mapTo[Int]
  def batchURLMigration(batchSize: Int) = actor.ref ! BatchURLMigration(batchSize)

}
