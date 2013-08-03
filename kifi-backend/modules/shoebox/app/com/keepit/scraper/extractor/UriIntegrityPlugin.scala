package com.keepit.scraper.extractor

import akka.util.Timeout
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.google.inject.Inject
import com.keepit.common.time._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.logging.Logging
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.actor.ActorProvider
import play.api.Plugin
import scala.concurrent.duration._


case class ChangedUri(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI])

class UriIntegrityActor @Inject()(
  db: Database,
  clock: Clock,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  collectionRepo: CollectionRepo,
  commentRepo: CommentRepo,
  deepLinkRepo: DeepLinkRepo,
  followRepo: FollowRepo,
  healthcheckPlugin: HealthcheckPlugin
) extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive = {
    case ChangedUri(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) => ???
  }

}

trait UriIntegrityPlugin extends Plugin {
  def fixChangedUri(change: ChangedUri): Unit
}

class UriIntegrityPluginImpl @Inject() (
  actorProvider: ActorProvider[UriIntegrityActor]
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
    actorProvider.actor ! change
  }
}