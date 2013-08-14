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
  healthcheckPlugin: HealthcheckPlugin
) extends FortyTwoActor(healthcheckPlugin) with Logging {

  private def preNormalize(uri: String): String = {uri}   // DUMMY

  /**
   * any reference to the old uri should be redirected to the new one
   */
  def handleMerge(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]): Unit = {
    if (oldUri == newUri) return
    db.readWrite{ implicit s =>
      changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUri))

      urlRepo.getByNormUri(oldUri).map{ url =>
        urlRepo.save(url.withNormUriId(newUri).withHistory(URLHistory(clock.now, newUri, URLHistoryCause.MERGE)))
      }

      val (u, v) = (uriRepo.get(oldUri), uriRepo.get(newUri))
      if ( u.state != NormalizedURIStates.ACTIVE && u.state != NormalizedURIStates.INACTIVE && (v.state == NormalizedURIStates.ACTIVE || v.state == NormalizedURIStates.INACTIVE)){
        uriRepo.save(v.withState(NormalizedURIStates.SCRAPE_WANTED))
      }
      uriRepo.save(u.withState(NormalizedURIStates.INACTIVE))

      urlRepo.getByNormUri(oldUri).map{ url =>
        val prepUrl = preNormalize(url.url)
        uriNormRuleRepo.save( UriNormalizationRule(prepUrl = prepUrl, mappedUrl = v.url, prepUrlHash = NormalizedURI.hashUrl(prepUrl)))
      }

      scrapeInfoRepo.getByUri(oldUri).map{ info =>
        scrapeInfoRepo.save(info.withState(ScrapeInfoStates.INACTIVE))
      }

      bookmarkRepo.getByUri(oldUri).map{ bm =>
        bookmarkRepo.save(bm.withNormUriId(newUri))
      }

      commentRepo.getByUri(oldUri).map{ cm =>
        commentRepo.save(cm.withNormUriId(newUri))
      }

      commentReadRepo.getByUri(oldUri).map{ cm =>
        commentReadRepo.save(cm.withNormUriId(newUri))
      }

      deepLinkRepo.getByUri(oldUri).map{ link =>
        deepLinkRepo.save(link.withNormUriId(newUri))
      }

      followRepo.getByUri(oldUri, excludeState = None).map{ follow =>
        followRepo.save(follow.withNormUriId(newUri))
      }
    }
  }

  /**
   * url now pointing to a new uri, any entity related to that url should update its uri reference.
   * This is NOT equivalent as a uri to uri migration. (Note the difference from the Merged case)
   */
  def handleSplit(url: URL, newUri: Id[NormalizedURI]): Unit = {
    db.readWrite { implicit s =>
      urlRepo.save(url.withNormUriId(newUri).withHistory(URLHistory(clock.now, newUri, URLHistoryCause.SPLIT)))
      val (u, v) = (uriRepo.get(url.normalizedUriId), uriRepo.get(newUri))
      if (u.state != NormalizedURIStates.ACTIVE && u.state != NormalizedURIStates.INACTIVE && (v.state == NormalizedURIStates.ACTIVE || v.state == NormalizedURIStates.INACTIVE))
        uriRepo.save(uriRepo.get(newUri).withState(NormalizedURIStates.SCRAPE_WANTED))

      bookmarkRepo.getByUrlId(url.id.get).map{ bm =>
        bookmarkRepo.save(bm.withNormUriId(newUri))
      }

      commentRepo.getByUrlId(url.id.get).map{ cm =>
        commentRepo.save(cm.withNormUriId(newUri))
      }

      deepLinkRepo.getByUrl(url.id.get).map{ link =>
        deepLinkRepo.save(link.withNormUriId(newUri))
      }

      followRepo.getByUrl(url.id.get, excludeState = None).map{ follow =>
        followRepo.save(follow.withNormUriId(newUri))
      }
    }
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
