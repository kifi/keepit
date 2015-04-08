package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.social.TwitterSocialGraph
import com.keepit.social.SocialNetworks
import com.keepit.model._
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.controllers.website.BookmarkImporter

import play.api.libs.json.JsObject
import scala.concurrent.{ ExecutionContext, Future }

case class TwitterStatusesAPIResponse(handle: String, tweets: Seq[JsObject], maxTweetId: Long)

@Singleton
class TwitterSyncCommander @Inject() (
    db: Database,
    syncStateRepo: TwitterSyncStateRepo,
    twitter: TwitterSocialGraph,
    socialRepo: SocialUserInfoRepo,
    importer: BookmarkImporter,
    libraryRepo: LibraryRepo,
    clock: Clock,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  private val throttle = new ReactiveLock(1)

  private def storeTweets(userId: Id[User], libraryId: Id[Library], tweets: Seq[JsObject]): Unit = {
    importer.processDirectTwitterData(userId, libraryId, tweets)
  }

  def internTwitterSync(userTokenToUse: Option[Id[User]], libraryId: Id[Library], handle: String): TwitterSyncState = {
    db.readWrite { implicit session =>
      syncStateRepo.getByHandleAndLibraryId(handle, libraryId) match {
        case Some(existing) if existing.state == TwitterSyncStateStates.ACTIVE =>
          existing
        case Some(notActive) =>
          syncStateRepo.save(notActive.copy(state = TwitterSyncStateStates.ACTIVE))
        case None => //create one!
          val sync = TwitterSyncState(userId = userTokenToUse, twitterHandle = handle, state = TwitterSyncStateStates.ACTIVE, libraryId = libraryId, maxTweetIdSeen = None, lastFetchedAt = None)
          syncStateRepo.save(sync)
      }
    }
  }

  def syncOne(socialUserInfo: Option[SocialUserInfo], state: TwitterSyncState, libraryOwner: Id[User]): Unit = throttle.withLockFuture {
    twitter.fetchTweets(socialUserInfo, state.twitterHandle, state.maxTweetIdSeen.getOrElse(0L)).map { tweets =>
      log.info(s"[TweetSync] Got ${tweets.length} tweets from ${state.twitterHandle}")
      if (tweets.nonEmpty) {
        val urls = tweets.map(_ \\ "expanded_url").map(_.toString())
        log.info(s"[TweetSync] Got urls: $urls")
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
    val states = db.readOnlyReplica { implicit session =>
      syncStateRepo.getSyncsToUpdate(clock.now.minusMinutes(15))
    }
    states.foreach { state =>
      val socialUserInfo: Option[SocialUserInfo] = state.userId.flatMap { userId =>
        db.readOnlyReplica { implicit session =>
          socialRepo.getByUser(userId).find(s => s.networkType == SocialNetworks.TWITTER)
        }
      }
      val library = db.readOnlyReplica { implicit session =>
        libraryRepo.get(state.libraryId)
      }
      if (library.state == LibraryStates.ACTIVE) {
        if (throttle.waiting < states.length) syncOne(socialUserInfo, state, library.ownerId)
        else airbrake.notify("Twitter library sync backing up!")
      } else {
        db.readWrite { implicit session =>
          syncStateRepo.save(state.copy(state = TwitterSyncStateStates.INACTIVE))
        }
      }
    }
  }

}
