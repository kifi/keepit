package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class TwitterWaitlistEntry(
    id: Option[Id[TwitterWaitlistEntry]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    twitterHandle: String,
    state: State[TwitterWaitlistEntry] = TwitterWaitlistEntryStates.ACTIVE) extends ModelWithState[TwitterWaitlistEntry] {
  def withId(id: Id[TwitterWaitlistEntry]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[TwitterWaitlistEntry]) = copy(state = state)
}

object TwitterWaitlistEntryStates extends States[TwitterWaitlistEntry] {
  val ACCEPTED = State[TwitterWaitlistEntry]("accepted")
}
