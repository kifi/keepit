package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._

case class SearchFriend(
    id: Option[Id[SearchFriend]] = None,
    userId: Id[User],
    friendId: Id[User],
    state: State[SearchFriend] = SearchFriendStates.EXCLUDED,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    seq: SequenceNumber = SequenceNumber.ZERO
    ) extends ModelWithState[SearchFriend] with ModelWithSeqNumber[SearchFriend] {
  def withId(id: Id[SearchFriend]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[SearchFriend]) = copy(state = state)
}

object SearchFriendStates {
  val INCLUDED = State[SearchFriend]("included")
  val EXCLUDED = State[SearchFriend]("excluded")
}
