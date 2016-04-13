package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.discussion.Message
import org.joda.time.DateTime

case class KeepTag(
    id: Option[Id[KeepTag]] = None,
    state: State[KeepTag] = KeepTagStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    tag: Hashtag,
    keepId: Id[Keep],
    messageId: Option[Id[Message]], // Foreign key to Eliza's ElizaMessage
    userId: Option[Id[User]]) extends ModelWithState[KeepTag] {
  def isActive: Boolean = state == KeepTagStates.ACTIVE
  def isInactive: Boolean = state == KeepTagStates.INACTIVE
  def withId(id: Id[KeepTag]): KeepTag = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepTag = this.copy(updatedAt = now)
  def withState(newState: State[KeepTag]): KeepTag = this.copy(state = newState)
  def withKeepId(newKeepId: Id[Keep]): KeepTag = this.copy(keepId = newKeepId)
  def sanitizeForDelete: KeepTag = this.withState(KeepTagStates.INACTIVE)
}

object KeepTagStates extends States[KeepTag]
