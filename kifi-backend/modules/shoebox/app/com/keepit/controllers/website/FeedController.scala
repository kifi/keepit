package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.seo.{ FeedCommander, LibrarySiteMapGenerator }
import com.keepit.common.time._
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._

class FeedController @Inject() (
    db: Database,
    clock: Clock,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    keepRepo: KeepRepo,
    ktlRepo: KeepToLibraryRepo,
    feedCommander: FeedCommander,
    fortyTwoConfig: FortyTwoConfig,
    airbrake: AirbrakeNotifier,
    val userActionsHelper: UserActionsHelper,
    generator: LibrarySiteMapGenerator) extends AdminUserActions {

  def getNewLibraries() = Action.async { request =>
    db.readOnlyReplicaAsync { implicit ro =>
      libraryRepo.getNewPublishedLibraries()
    } flatMap { libs =>
      val feedUrl = "whatever" //s"${fortyTwoConfig.applicationBaseUrl}${com.keepit.controllers.website.routes.FeedController.getNewLibraries().url.toString()}"
      feedCommander.rss("New Libraries on Kifi", feedUrl, libs) map { rss =>
        Result(
          header = ResponseHeader(200, Map(CONTENT_TYPE -> "application/rss+xml")),
          body = feedCommander.wrap(rss)
        )
      }
    }
  }

  def getTopLibraries() = Action.async { request =>
    db.readOnlyReplicaAsync { implicit s =>
      val tops = libraryMembershipRepo.mostMembersSince(30, clock.now().minusHours(24))
      val keepCountsByLibrary = ktlRepo.getCountsByLibraryIds(tops.map(_._1).toSet)
      val libIds = tops.filter {
        case (libId, numMembers) => keepCountsByLibrary.get(libId).exists(_ >= 3)
      } map (_._1)
      val libMap = libraryRepo.getActiveByIds(libIds.toSet)
      libIds.flatMap(libMap.get)
    }.flatMap { libs =>
      val feedUrl = "whatever" //s"${fortyTwoConfig.applicationBaseUrl}${com.keepit.controllers.website.routes.FeedController.getTopLibraries().url.toString()}"
      feedCommander.rss("Top Libraries on Kifi", feedUrl, libs) map { rss =>
        Result(
          header = ResponseHeader(200, Map(CONTENT_TYPE -> "application/rss+xml")),
          body = feedCommander.wrap(rss)
        )
      }
    }
  }

}
