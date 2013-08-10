package com.keepit.scraper.extractor

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


case class ChangedUri(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI])

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
  healthcheckPlugin: HealthcheckPlugin
) extends FortyTwoActor(healthcheckPlugin) with Logging {

  /**
   * any reference to the old uri should be redirected to the new one
   */
  def handleChanged(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) = {
    db.readWrite{ implicit s =>
      urlRepo.getByNormUri(oldUri).map{ url =>
        urlRepo.save(url.withNormUriId(newUri).withHistory(URLHistory(clock.now, newUri, URLHistoryCause.MERGE)))
      }

      uriRepo.save(uriRepo.get(oldUri).withState(NormalizedURIStates.INACTIVE))

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

      scrapeInfoRepo.getByUri(oldUri).map{ info =>
        scrapeInfoRepo.save(info.withState(ScrapeInfoStates.INACTIVE))
      }

    }
  }

  def receive = {
    case ChangedUri(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) => handleChanged(oldUri, newUri)
  }

}

@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends Plugin {
  def fixChangedUri(change: ChangedUri): Unit
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

  override def fixChangedUri(change: ChangedUri) = {
    actor.ref ! change
  }
}
