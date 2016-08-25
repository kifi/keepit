package com.keepit.commanders

import java.util.UUID

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.TwitterSyncError.{ HandleDoesntExist, TokenExpired }
import com.keepit.common.social.{ TwitterSocialGraph, TwitterSyncError }
import com.keepit.common.time._
import com.keepit.controllers.website.{ TwitterArchiveParser, Bookmark, BookmarkImporter }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model.SyncTarget.{ Favorites, Tweets }
import com.keepit.model._
import com.keepit.social.SocialNetworks
import com.keepit.social.twitter.TwitterHandle
import play.api.libs.json.{ JsArray, JsObject, Json }

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
    rawKeepInterner: RawKeepInterner,
    twitterArchiveParser: TwitterArchiveParser,
    heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  private val throttle = new ReactiveLock(1)

  def internTwitterSync(userTokenToUse: Option[Id[User]], libraryId: Id[Library], handle: TwitterHandle, target: SyncTarget): TwitterSyncState = {
    db.readWrite { implicit session =>
      syncStateRepo.getByHandleAndLibraryId(handle, libraryId, target) match {
        case Some(existing) if existing.state == TwitterSyncStateStates.ACTIVE =>
          existing
        case Some(notActive) =>
          syncStateRepo.save(notActive.copy(state = TwitterSyncStateStates.ACTIVE))
        case None => //create one!
          val sync = TwitterSyncState(userId = userTokenToUse, twitterHandle = handle, state = TwitterSyncStateStates.ACTIVE, libraryId = libraryId, lastFetchedAt = None, minTweetIdSeen = None, maxTweetIdSeen = None, target = target)
          syncStateRepo.save(sync)
      }
    }
  }

  def syncOne(socialUserInfo: Option[SocialUserInfo], state: TwitterSyncState, keeperUserId: Id[User]): Unit = throttle.withLockFuture {
    val fetcher = {
      state.target match {
        case Favorites => twitter.fetchHandleFavourites _
        case Tweets => twitter.fetchHandleTweets _
      }
    }

    def errorHandler(r: Either[TwitterSyncError, Seq[JsObject]]) = {
      r match {
        case Right(xs) => xs
        case Left(err) => handleSyncFetchFailure(err); Seq.empty
      }
    }

    def fetchAllNewerThanState(syncState: TwitterSyncState, upperBound: Option[Long]): Future[TwitterSyncState] = {
      (syncState.maxTweetIdSeen, upperBound) match {
        case (Some(max), Some(batchUb)) if batchUb <= max => // Batch upper bound less than existing max id
          Future.successful(syncState)
        case _ =>
          fetcher(socialUserInfo, syncState.twitterHandle, syncState.maxTweetIdSeen, upperBound)
            .map(errorHandler)
            .map(persistTweets(syncState, keeperUserId, _))
            .flatMap {
              case (newSyncState, Some(batchMin), _) =>
                fetchAllNewerThanState(newSyncState, Some(batchMin))
              case (newSyncState, _, _) =>
                Future.successful(newSyncState)
            }
      }
    }

    def fetchAllOlderThanState(syncState: TwitterSyncState, upperBound: Option[Long]): Future[TwitterSyncState] = {
      (syncState.minTweetIdSeen, upperBound) match {
        case (Some(min), Some(batchUb)) if batchUb > min => // Batch upper bound greater than existing min id
          Future.successful(syncState)
        case _ =>
          fetcher(socialUserInfo, syncState.twitterHandle, None, upperBound)
            .map(errorHandler)
            .map(persistTweets(syncState, keeperUserId, _))
            .flatMap {
              case (newSyncState, Some(batchMin), _) =>
                fetchAllOlderThanState(newSyncState, Some(batchMin))
              case (newSyncState, _, _) =>
                Future.successful(newSyncState)
            }
      }
    }

    fetchAllNewerThanState(state, None).flatMap { newState =>
      fetchAllOlderThanState(newState, newState.minTweetIdSeen)
    }
  }

  private def handleSyncFetchFailure(error: TwitterSyncError): Unit = {
    error match {
      case TokenExpired(Some(sui)) =>
        db.readWrite { implicit s =>
          socialRepo.save(sui.copy(state = SocialUserInfoStates.TOKEN_EXPIRED))
        }
      case HandleDoesntExist(handle) =>
        db.readWrite { implicit s =>
          syncStateRepo.getAllByHandle(handle).map { twitterSync =>
            syncStateRepo.save(twitterSync.copy(state = TwitterSyncStateStates.INACTIVE))
          }
        }
      case other =>
        log.warn(s"[twfetch-err] Fetching error $other")
    }
  }

  private def persistTweets(syncState: TwitterSyncState, keeperUserId: Id[User], tweets: Seq[JsObject]) = {
    if (tweets.nonEmpty) {
      log.debug(s"[TweetSync] Got ${tweets.length} tweets from ${syncState.twitterHandle}")

      processDirectTwitterData(keeperUserId, syncState.libraryId, tweets)

      val tweetIds = tweets.map(tweet => (tweet \ "id").as[Long])
      val batchMinTweetId = tweetIds.min
      val batchMaxTweetId = tweetIds.max

      val newMin = syncState.minTweetIdSeen.map(Math.min(_, batchMinTweetId)).getOrElse(batchMinTweetId)
      val newMax = syncState.maxTweetIdSeen.map(Math.max(_, batchMaxTweetId)).getOrElse(batchMaxTweetId)

      val newState = db.readWrite { implicit session =>
        syncStateRepo.save(syncState.copy(lastFetchedAt = Some(clock.now), maxTweetIdSeen = Some(newMax), minTweetIdSeen = Some(newMin)))
      }
      (newState, Some(batchMinTweetId), Some(batchMaxTweetId))
    } else {
      val newState = db.readWrite { implicit session =>
        syncStateRepo.save(syncState.copy(lastFetchedAt = Some(clock.now)))
      }
      (newState, None, None)
    }
  }

  private def processDirectTwitterData(userId: Id[User], libraryId: Id[Library], tweets: Seq[JsObject]): Unit = {
    implicit val context = heimdalContextBuilderFactoryBean().build
    val (sourceOpt, parsed) = twitterArchiveParser.parseTwitterJson(tweets)

    val (importId, rawKeeps) = rawKeepInterner.generateRawKeeps(userId, sourceOpt, parsed, libraryId)

    rawKeepInterner.persistRawKeeps(rawKeeps, Some(importId))
  }

  private val safeBacklogBuffer = 10
  private val fetchEveryMins = 15
  def syncAll(): Unit = {
    return ; // ewwww
    val states = db.readOnlyReplica { implicit session =>
      syncStateRepo.getSyncsToUpdate(clock.now.minusMinutes(fetchEveryMins))
    }

    if (states.length + safeBacklogBuffer < throttle.waiting) { // it's backed up more than what we're trying to bring in, so is getting worse.
      airbrake.notify(s"Twitter library sync backing up! Would like to sync ${states.length} more, waiting on ${throttle.waiting}")
    } else {
      states.foreach { state =>
        if (throttle.waiting < states.length + 1) {
          val (socialUserInfo, library) = db.readOnlyReplica { implicit session =>
            val socialUserInfo = state.userId.map { userId =>
              // Grab Twitter SUIs, prefer ones that are "fetched_using_self", which is the state for active, working records
              socialRepo.getByUser(userId).filter(s => s.networkType == SocialNetworks.TWITTER).sortBy(sui => sui.state == SocialUserInfoStates.FETCHED_USING_SELF).reverse.headOption
            }
            val library = libraryRepo.get(state.libraryId)

            (socialUserInfo, library)
          }

          socialUserInfo match {
            case Some(suiOpt) if suiOpt.isEmpty || suiOpt.exists(_.state == SocialUserInfoStates.TOKEN_EXPIRED) =>
              // We are using a user's SUI, but it's not valid. Queue to try again later.
              db.readWrite { implicit session =>
                syncStateRepo.save(state.copy(lastFetchedAt = Some(clock.now)))
              }
            case _ if library.state == LibraryStates.ACTIVE =>
              // We're using a user's SUI (and it's ready) or are explicitly not using a SUI (so using Kifi's token)
              syncOne(socialUserInfo.flatten, state, library.ownerId)
            case _ =>
              db.readWrite { implicit session =>
                syncStateRepo.save(state.copy(state = TwitterSyncStateStates.INACTIVE))
              }
          }
        }
      }
    }
  }

}
