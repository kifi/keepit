package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class SearchFriend(
    id: Option[Id[SearchFriend]] = None,
    userId: Id[User],
    friendId: Id[User],
    state: State[SearchFriend] = SearchFriendStates.EXCLUDED,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    seq: SequenceNumber[SearchFriend] = SequenceNumber.ZERO) extends ModelWithState[SearchFriend] with ModelWithSeqNumber[SearchFriend] {
  def withId(id: Id[SearchFriend]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[SearchFriend]) = copy(state = state)
}

object SearchFriend {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[SearchFriend]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'friendId).format(Id.format[User]) and
    (__ \ 'state).format(State.format[SearchFriend]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'seq).format(SequenceNumber.format[SearchFriend])
  )(SearchFriend.apply, unlift(SearchFriend.unapply))
}

object SearchFriendStates {
  val INCLUDED = State[SearchFriend]("included")
  val EXCLUDED = State[SearchFriend]("excluded")
}
