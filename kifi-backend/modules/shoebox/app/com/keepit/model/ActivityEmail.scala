package com.keepit.model

import com.keepit.common.db.{ ModelWithState, State, Id }

import com.keepit.common.time._
import org.joda.time.DateTime

case class ActivityEmail(
    id: Option[Id[ActivityEmail]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ActivityEmail] = ActivityEmailStates.PENDING,
    userId: Id[User],
    otherFollowedLibraries: Option[Seq[Id[Library]]],
    userFollowedLibraries: Option[Seq[Id[Library]]],
    libraryRecommendations: Option[Seq[Id[Library]]]) extends ModelWithState[ActivityEmail] {
  def withId(id: Id[ActivityEmail]): ActivityEmail = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
}
