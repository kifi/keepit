package com.keepit.integrity

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.time._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.akka.FortyTwoActor
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
case class SplittedUri(url: URL, newUri: Id[NormalizedURI]) extends UriChangeMessage
case class BatchUpdateMerge(batchSize: Int)

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
  centralConfig: CentralConfig,
  healthcheckPlugin: HealthcheckPlugin,
  scraper: ScraperPlugin
) extends FortyTwoActor(healthcheckPlugin) with Logging {

  private def handleBookmarks(oldUserBookmarks: Map[Id[User], Seq[Bookmark]], newUriId: Id[NormalizedURI])(implicit session: RWSession) = {
    val deactivatedBms = oldUserBookmarks.map{ case (userId, bms) =>
      val oldBm = bms.head
      assume(bms.size == 1, s"user ${userId.id} has multiple bookmarks referencing uri ${oldBm.uriId}")
      bookmarkRepo.getByUriAndUser(newUriId, userId, excludeState = None) match {
        case None => {
          log.info(s"going to redirect bookmark's uri: (userId, newUriId) = (${userId.id}, ${newUriId.id}), db or cache returns None")
          bookmarkRepo.removeFromCache(oldBm)     // NOTE: we touch two different cache keys here and the following line
          bookmarkRepo.removeFromCache(oldBm.withNormUriId(newUriId))   // tmp hack
          bookmarkRepo.save(oldBm.withNormUriId(newUriId)); None
        } 
        case Some(bm) => if (oldBm.state == BookmarkStates.ACTIVE) {
          log.info("not going to redirect bookmark's uri")
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
   * any reference to the old uri should be redirected to the new one
   */
  private def processMerge(change: ChangedURI)(implicit session: RWSession): (Option[NormalizedURI], Option[ChangedURI]) = {
    val (oldUriId, newUriId) = (change.oldUriId, change.newUriId)
    if (oldUriId == newUriId || change.state != ChangedURIStates.ACTIVE) { 
      if (oldUriId == newUriId) changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.INACTIVE)))
      (None, None) 
    } else {
      val oldUri = uriRepo.get(oldUriId)
      val newUri = uriRepo.get(newUriId) match {
        case uri if uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.REDIRECTED => uriRepo.save(uri.copy(state = NormalizedURIStates.ACTIVE, redirect = None, redirectTime = None))
        case uri => uri
      }

      urlRepo.getByNormUri(oldUriId).map{ url =>
        urlRepo.save(url.withNormUriId(newUriId).withHistory(URLHistory(clock.now, newUriId, URLHistoryCause.MERGE)))
      }

      val toBeScraped = if ( oldUri.state != NormalizedURIStates.ACTIVE && oldUri.state != NormalizedURIStates.INACTIVE && (newUri.state == NormalizedURIStates.ACTIVE || newUri.state == NormalizedURIStates.INACTIVE)){
        Some(uriRepo.save(newUri.withState(NormalizedURIStates.SCRAPE_WANTED)))
      } else None
      
      uriRepo.getByRedirection(oldUri.id.get).foreach{ uri =>
        uriRepo.save(uri.withRedirect(newUriId, currentDateTime))
      }  
        
      uriRepo.save(oldUri.withRedirect(newUriId, currentDateTime))

      /**
       * ensure uniqueness of bookmarks during merge.
       */
      val oldUserBms = bookmarkRepo.getByUri(oldUriId, excludeState = None).groupBy(_.userId)
      handleBookmarks(oldUserBms, newUriId)

      commentReadRepo.getByUri(oldUriId).map{ cm =>
        commentReadRepo.save(cm.withNormUriId(newUriId))
      }

      deepLinkRepo.getByUri(oldUriId).map{ link =>
        deepLinkRepo.save(link.withNormUriId(newUriId))
      }

      followRepo.getByUri(oldUriId, excludeState = None).map{ follow =>
        followRepo.save(follow.withNormUriId(newUriId))
      }
      
      val saved = changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.APPLIED)))
      
      (toBeScraped, Some(saved))
    }
  }

  /**
   * url now pointing to a new uri, any entity related to that url should update its uri reference.
   * This is NOT equivalent as a uri to uri migration. (Note the difference from the Merged case)
   */
  private def handleSplit(url: URL, newUriId: Id[NormalizedURI]): Unit = {
    val toBeScraped = db.readWrite { implicit s =>
      urlRepo.save(url.withNormUriId(newUriId).withHistory(URLHistory(clock.now, newUriId, URLHistoryCause.SPLIT)))
      val (oldUri, newUri) = (uriRepo.get(url.normalizedUriId), uriRepo.get(newUriId))
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
      toBeScraped
    }
    toBeScraped.map(scraper.asyncScrape(_))
  }

  private def batchUpdateMerge(batchSize: Int): Int = {
    val toMerge = getOverDueList(batchSize)
    log.info(s"batch merge uris: ${toMerge.size} pair of uris to be merged")
    val toScrapeAndSavedChange = db.readWrite{ implicit s =>
      toMerge.map{ change => processMerge(change) }
    }
    toScrapeAndSavedChange.map(_._2).filter(_.isDefined).sortBy(_.get.seq).lastOption.map{ x => centralConfig.update(new ChangedUriSeqNumKey(), x.get.seq.value) }
    log.info(s"batch merge uris completed in database: ${toMerge.size} pair of uris merged. zookeeper seqNum updated. start scraping ${toScrapeAndSavedChange.size} pages")
    toScrapeAndSavedChange.map(_._1).filter(_.isDefined).groupBy(_.get.url).mapValues(_.head).values.map{ x => scraper.asyncScrape(x.get)}
    toMerge.size
  }

  private def getOverDueList(fetchSize: Int = -1) = {
    centralConfig(new ChangedUriSeqNumKey()) match {
      case None => db.readOnly{ implicit s => changedUriRepo.getChangesSince(SequenceNumber(0), fetchSize, state = ChangedURIStates.ACTIVE)}
      case Some(seqNum) => db.readOnly{ implicit s => changedUriRepo.getChangesSince(SequenceNumber(seqNum), fetchSize, state = ChangedURIStates.ACTIVE)}
    }
  }

  def receive = {
    case BatchUpdateMerge(batchSize) => Future.successful(batchUpdateMerge(batchSize)) pipeTo sender
    case MergedUri(oldUri, newUri) => db.readWrite{ implicit s => changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUri)) }   // process later
    case SplittedUri(url, newUri) => handleSplit(url, newUri)
  }

}


@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends SchedulingPlugin  {
  def handleChangedUri(change: UriChangeMessage): Unit
  def batchUpdateMerge(batchSize: Int = -1): Future[Int]
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
  }
  override def onStop() {
     log.info("stopping UriIntegrityPluginImpl")
  }

  def handleChangedUri(change: UriChangeMessage) = {
    actor.ref ! change
  }
  
  def batchUpdateMerge(batchSize: Int) = actor.ref.ask(BatchUpdateMerge(batchSize))(1 minute).mapTo[Int]

}
