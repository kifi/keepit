package com.keepit.model

import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
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
    twitterHandle: String,
    lastFetchedAt: Option[DateTime],
    libraryId: Id[Library],
    maxTweetIdSeen: Option[Long]) extends ModelWithState[TwitterSyncState] {
  def withId(id: Id[TwitterSyncState]): TwitterSyncState = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
}

object TwitterSyncStateStates extends States[TwitterSyncState]

case class TwitterHandleLibraryIdKey(val id: Id[Library]) extends Key[String] {
  override val version = 1
  val namespace = "twitter_handle_library_id"
  def toKey(): String = id.id.toString
}

class TwitterHandleCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[TwitterHandleLibraryIdKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
