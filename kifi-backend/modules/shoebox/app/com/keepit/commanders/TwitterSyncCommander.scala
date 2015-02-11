package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.social.TwitterSocialGraph
import com.keepit.social.SocialNetworks
import com.keepit.model.{ User, TwitterSyncStateRepo, SocialUserInfoRepo, Library, SocialUserInfo, TwitterSyncState, LibraryRepo }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.controllers.website.BookmarkImporter

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.JsObject
import scala.concurrent.Future

case class TwitterStatusesAPIResponse(handle: String, tweets: Seq[JsObject], maxTweetId: Long)

@Singleton
class TwitterSyncCommander @Inject() (
    db: Database,
    syncStateRepo: TwitterSyncStateRepo,
    twitter: TwitterSocialGraph,
    socialRepo: SocialUserInfoRepo,
    importer: BookmarkImporter,
    libraryRepo: LibraryRepo,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  val throttle = new ReactiveLock(1)

  private def storeTweets(userId: Id[User], libraryId: Id[Library], tweets: Seq[JsObject]): Unit = {
    importer.processDirectTwitterData(userId, libraryId, tweets)
  }

  private def syncOne(socialUserInfo: Option[SocialUserInfo], state: TwitterSyncState, libraryOwner: Id[User]): Unit = throttle.withLockFuture {
    twitter.fetchTweets(socialUserInfo, state.twitterHandle, state.maxTweetIdSeen.getOrElse(0)).map { tweets =>
      if (!tweets.isEmpty) {
        val newMaxTweetId = tweets.map(tweet => (tweet \ "id").as[Long]).max
        storeTweets(libraryOwner, state.libraryId, tweets)
        val newState = state.copy(lastFetchedAt = Some(currentDateTime), maxTweetIdSeen = Some(newMaxTweetId))
        db.readWrite { implicit session =>
          syncStateRepo.save(newState)
        }
        syncOne(socialUserInfo, newState, libraryOwner)
      }
    }
  }

  def syncAll(): Unit = {
    val states = db.readOnlyMaster { implicit session =>
      syncStateRepo.getSyncsToUpdate(currentDateTime.minusMinutes(15))
    }
    states.map { state =>
      val socialUserInfo: Option[SocialUserInfo] = state.userId.flatMap { userId =>
        db.readOnlyReplica { implicit session =>
          socialRepo.getByUser(userId).find(_.networkType == SocialNetworks.TWITTER)
        }
      }
      val ownerId = db.readOnlyReplica { implicit session =>
        libraryRepo.get(state.libraryId).ownerId
      }
      if (throttle.waiting < states.length) syncOne(socialUserInfo, state, ownerId)
      else airbrake.notify("Twitter library sync backing up!")
    }
  }

}
