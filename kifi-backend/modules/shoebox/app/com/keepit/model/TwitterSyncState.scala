package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db.{ ModelWithState, State, Id, States }
import com.keepit.common.time._

case class TwitterSyncState(
    id: Option[Id[TwitterSyncState]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[TwitterSyncState] = TwitterSyncStateStates.ACTIVE,
    userId: Option[Id[User]],
    twitterHandle: String,
    lastFetchedAt: Option[DateTime],
    libraryId: Id[Library],
    maxTweetIdSeen: Option[Long]) extends ModelWithState[TwitterSyncState] {
  def withId(id: Id[TwitterSyncState]): TwitterSyncState = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
}

object TwitterSyncStateStates extends States[TwitterSyncState]
