package com.keepit.integrity

import akka.util.Timeout
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.google.inject.{ImplementedBy, Inject}
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

trait ChangedUri

case class MergedUri(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) extends ChangedUri
case class SplittedUri(url: URL, newUri: Id[NormalizedURI]) extends ChangedUri

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
  def handleMerge(oldUriId: Id[NormalizedURI], newUriId: Id[NormalizedURI]): Unit = {
    if (oldUriId == newUriId) return
    val toBeScraped = db.readWrite{ implicit s =>
      changedUriRepo.save(ChangedURI(oldUriId = oldUriId, newUriId = newUriId))

      val (oldUri, newUri) = (uriRepo.get(oldUriId), uriRepo.get(newUriId))

      urlRepo.getByNormUri(oldUriId).map{ url =>
        val prepUrl = prenormalize(url.url)
        uriNormRuleRepo.save( UriNormalizationRule(prepUrl = prepUrl, mappedUrl = newUri.url, prepUrlHash = NormalizedURI.hashUrl(prepUrl)))
        urlRepo.save(url.withNormUriId(newUriId).withHistory(URLHistory(clock.now, newUriId, URLHistoryCause.MERGE)))
      }

      val toBeScraped = if ( oldUri.state != NormalizedURIStates.ACTIVE && oldUri.state != NormalizedURIStates.INACTIVE && (newUri.state == NormalizedURIStates.ACTIVE || newUri.state == NormalizedURIStates.INACTIVE)){
          Some(uriRepo.save(newUri.withState(NormalizedURIStates.SCRAPE_WANTED)))
        } else None

      uriRepo.save(oldUri.withState(NormalizedURIStates.INACTIVE).withRedirect(newUriId, currentDateTime))

      scrapeInfoRepo.getByUri(oldUriId).map{ info =>
        scrapeInfoRepo.save(info.withState(ScrapeInfoStates.INACTIVE))
      }

      bookmarkRepo.getByUri(oldUriId).map{ bm =>
        bookmarkRepo.save(bm.withNormUriId(newUriId))
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
      toBeScraped
    }
    toBeScraped.map(scraper.asyncScrape(_))
  }

  /**
   * url now pointing to a new uri, any entity related to that url should update its uri reference.
   * This is NOT equivalent as a uri to uri migration. (Note the difference from the Merged case)
   */
  def handleSplit(url: URL, newUriId: Id[NormalizedURI]): Unit = {
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

  def receive = {
    case MergedUri(oldUri, newUri) => handleMerge(oldUri, newUri)
    case SplittedUri(url, newUri) => handleSplit(url, newUri)
  }

}

@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends Plugin {
  def handleChangedUri(change: ChangedUri): Unit
}

class UriIntegrityPluginImpl @Inject() (
  actor: ActorInstance[UriIntegrityActor]
) extends UriIntegrityPlugin with Logging {
  implicit val actorTimeout = Timeout(5 seconds)

  override def enabled: Boolean = true

  override def onStart() {
     log.info("starting UriIntegrityPluginImpl")
  }
  override def onStop() {
     log.info("stopping UriIntegrityPluginImpl")
  }

  override def handleChangedUri(change: ChangedUri) = {
    actor.ref ! change
  }
}
