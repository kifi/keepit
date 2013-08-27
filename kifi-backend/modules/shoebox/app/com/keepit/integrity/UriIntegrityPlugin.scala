package com.keepit.integrity

import akka.util.Timeout
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.time._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.logging.Logging
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.actor.ActorInstance
import play.api.Plugin
import scala.concurrent.duration._
import com.keepit.common.net.URI
import scala.util.{Success, Failure, Try}
import com.keepit.normalizer.Prenormalizer
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.db.slick.DBSession.RWSession

trait ChangedUri

case class MergedUri(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) extends ChangedUri
case class SplittedUri(url: URL, newUri: Id[NormalizedURI]) extends ChangedUri
case object BatchUpdateMerge

class UriIntegrityActor @Inject()(
  db: Database,
  clock: Clock,
  urlHashCache: NormalizedURIUrlHashCache,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  collectionRepo: CollectionRepo,
  commentRepo: CommentRepo,
  commentReadRepo: CommentReadRepo,
  deepLinkRepo: DeepLinkRepo,
  followRepo: FollowRepo,
  scrapeInfoRepo: ScrapeInfoRepo,
  uriNormRuleRepo: UriNormalizationRuleRepo,
  changedUriRepo: ChangedURIRepo,
  centralConfig: CentralConfig,
  healthcheckPlugin: HealthcheckPlugin,
  scraper: ScraperPlugin
) extends FortyTwoActor(healthcheckPlugin) with Logging {

  private def prenormalize(url: String): String = {
    val prepUrlTry = for {
      uri <- URI.parse(url)
      prepUrl <- Try { Prenormalizer(uri).toString() }
    } yield prepUrl

    prepUrlTry match {
      case Success(prepUrl) => prepUrl
      case Failure(e) => {
        healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Static Normalization failed: ${e.getMessage}")))
        url
      }
    }
  }

  /**
   * any reference to the old uri should be redirected to the new one
   */
  private def processMerge(change: ChangedURI)(implicit session: RWSession): (Option[NormalizedURI], Option[ChangedURI]) = {
    val (oldUriId, newUriId) = (change.oldUriId, change.newUriId)
    if (oldUriId == newUriId || change.state != ChangedURIStates.ACTIVE) { 
      if (oldUriId == newUriId) changedUriRepo.save(change.withState(ChangedURIStates.INACTIVE))
      (None, None) 
    } else {
      val (oldUri, newUri) = (uriRepo.get(oldUriId), uriRepo.get(newUriId))

      urlRepo.getByNormUri(oldUriId).map{ url =>
        val prepUrl = prenormalize(url.url)
        uriNormRuleRepo.save( UriNormalizationRule(prepUrl = prepUrl, mappedUrl = newUri.url, prepUrlHash = NormalizedURI.hashUrl(prepUrl)))
        urlRepo.save(url.withNormUriId(newUriId).withHistory(URLHistory(clock.now, newUriId, URLHistoryCause.MERGE)))
      }

      val toBeScraped = if ( oldUri.state != NormalizedURIStates.ACTIVE && oldUri.state != NormalizedURIStates.INACTIVE && (newUri.state == NormalizedURIStates.ACTIVE || newUri.state == NormalizedURIStates.INACTIVE)){
          Some(uriRepo.save(newUri.withState(NormalizedURIStates.SCRAPE_WANTED)))
      } else None
      
      uriRepo.getByRedirection(oldUri.id.get).foreach{ uri =>
        uriRepo.save(uri.withRedirect(newUriId, currentDateTime))  
      }  
        
      uriRepo.save(oldUri.withState(NormalizedURIStates.INACTIVE).withRedirect(newUriId, currentDateTime))

      scrapeInfoRepo.getByUri(oldUriId).map{ info =>
        scrapeInfoRepo.save(info.withState(ScrapeInfoStates.INACTIVE))
      }

      bookmarkRepo.getByUri(oldUriId).groupBy(_.userId).foreach{ case (userId, bms) =>
        bms.foreach{ bm => bookmarkRepo.save(bm.withActive(false)) }
        if (bookmarkRepo.getByUriAndUser(newUriId, userId).size == 0) bms.take(1).map{ bm => bookmarkRepo.save(bm.withNormUriId(newUriId).withActive(true)) }        
      }

      commentRepo.getByUri(oldUriId).map{ cm =>
        commentRepo.save(cm.withNormUriId(newUriId))
      }

      commentReadRepo.getByUri(oldUriId).map{ cm =>
        commentReadRepo.save(cm.withNormUriId(newUriId))
      }

      deepLinkRepo.getByUri(oldUriId).map{ link =>
        deepLinkRepo.save(link.withNormUriId(newUriId))
      }

      followRepo.getByUri(oldUriId, excludeState = None).map{ follow =>
        followRepo.save(follow.withNormUriId(newUriId))
      }
      
      val saved = changedUriRepo.save(change.withState(ChangedURIStates.APPLIED))
      
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

      bookmarkRepo.getByUrlId(url.id.get).map{ bm =>
        bookmarkRepo.save(bm.withNormUriId(newUriId))
      }

      commentRepo.getByUrlId(url.id.get).map{ cm =>
        commentRepo.save(cm.withNormUriId(newUriId))
      }

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
  
  private def batchUpdateMerge() = {
    val toMerge = getOverDueList(fetchSize = 50)
    log.info(s"batch merge uris: ${toMerge.size} pair of uris to be merged")
    val toScrapeAndSavedChange = db.readWrite{ implicit s =>
      toMerge.map{ change => processMerge(change) }
    }
    toScrapeAndSavedChange.map(_._2).filter(_.isDefined).sortBy(_.get.seq).lastOption.map{ x => centralConfig.update(new ChangedUriSeqNumKey(), x.get.seq.value) }
    log.info(s"batch merge uris completed in database: ${toMerge.size} pair of uris merged. zookeeper seqNum updated. start scraping ${toScrapeAndSavedChange.size} pages")
    toScrapeAndSavedChange.map(_._1).filter(_.isDefined).map{ x => scraper.asyncScrape(x.get)}
  }
  
  private def getOverDueList(fetchSize: Int = -1) = {
    centralConfig(new ChangedUriSeqNumKey()) match {
      case None => db.readOnly{ implicit s => changedUriRepo.getChangesSince(SequenceNumber(0), fetchSize, state = ChangedURIStates.ACTIVE)}
      case Some(seqNum) => db.readOnly{ implicit s => changedUriRepo.getChangesSince(SequenceNumber(seqNum), fetchSize, state = ChangedURIStates.ACTIVE)}
    }
  }

  def receive = {
    case BatchUpdateMerge => batchUpdateMerge()
    case MergedUri(oldUri, newUri) => db.readWrite{ implicit s => changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUri)) }   // process later
    case SplittedUri(url, newUri) => handleSplit(url, newUri)
  }

}


@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends SchedulingPlugin  {
  def handleChangedUri(change: ChangedUri): Unit
  def batchUpdateMerge(): Unit
}

@Singleton
class UriIntegrityPluginImpl @Inject() (
  actor: ActorInstance[UriIntegrityActor],
  val schedulingProperties: SchedulingProperties
) extends UriIntegrityPlugin with Logging {
  override def enabled = true
  override def onStart() {
     log.info("starting UriIntegrityPluginImpl")
     scheduleTask(actor.system, 1 minutes, 15 seconds, actor.ref, BatchUpdateMerge)
  }
  override def onStop() {
     log.info("stopping UriIntegrityPluginImpl")
  }

  override def handleChangedUri(change: ChangedUri) = {
    actor.ref ! change
  }
  
  override def batchUpdateMerge() = actor.ref ! BatchUpdateMerge
}
