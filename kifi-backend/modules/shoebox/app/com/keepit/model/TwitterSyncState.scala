package com.keepit.model

import com.keepit.common.cache._
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.logging.AccessLog
import com.keepit.common.reflection.Enumerator
import com.keepit.social.twitter.TwitterHandle
import org.joda.time.DateTime

import com.keepit.common.db.{ ModelWithState, State, Id, States }
import com.keepit.common.time._

import scala.concurrent.duration.Duration

case class TwitterSyncState(
    id: Option[Id[TwitterSyncState]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[TwitterSyncState] = TwitterSyncStateStates.ACTIVE,
    userId: Option[Id[User]], // Id of user to use to sync with Twitter
    twitterHandle: TwitterHandle,
    lastFetchedAt: Option[DateTime],
    libraryId: Id[Library],
    maxTweetIdSeen: Option[Long],
    minTweetIdSeen: Option[Long],
    target: SyncTarget) extends ModelWithState[TwitterSyncState] {
  def withId(id: Id[TwitterSyncState]): TwitterSyncState = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
}

object TwitterSyncState {
  def applyFromDbRow(
    id: Option[Id[TwitterSyncState]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[TwitterSyncState],
    userId: Option[Id[User]],
    twitterHandle: TwitterHandle,
    lastFetchedAt: Option[DateTime],
    libraryId: Id[Library],
    maxTweetIdSeen: Option[Long],
    minTweetIdSeen: Option[Long],
    target: Option[SyncTarget]) = {
    TwitterSyncState(id, createdAt, updatedAt, state, userId, twitterHandle, lastFetchedAt, libraryId, maxTweetIdSeen, minTweetIdSeen, target.getOrElse(SyncTarget.Tweets))
  }

  def unapplyToDbRow(t: TwitterSyncState) = Option {
    (t.id, t.createdAt, t.updatedAt, t.state, t.userId, t.twitterHandle, t.lastFetchedAt, t.libraryId, t.maxTweetIdSeen, t.minTweetIdSeen, Option(t.target))
  }
}

object TwitterSyncStateStates extends States[TwitterSyncState]

sealed abstract class SyncTarget(val value: String)
object SyncTarget extends Enumerator[SyncTarget] {
  case object Tweets extends SyncTarget("tweets")
  case object Favorites extends SyncTarget("favorites")

  def get(kind: String) = _all.find(_.value == kind).getOrElse(Tweets)
}

case class TwitterHandleLibraryIdKey(id: Id[Library]) extends Key[TwitterHandle] {
  override val version = 2
  val namespace = "twitter_handle_library_id"
  def toKey(): String = id.id.toString
}

class TwitterHandleCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[TwitterHandleLibraryIdKey, TwitterHandle](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
