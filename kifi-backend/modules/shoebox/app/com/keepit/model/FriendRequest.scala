package com.keepit.model

import scala.Some

import org.joda.time.DateTime

import com.keepit.common.db.{Model, State, Id}
import com.keepit.common.time._

case class FriendRequest(
    id: Option[Id[FriendRequest]] = None,
    senderId: Id[User],
    recipientId: Id[User],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[FriendRequest] = FriendRequestStates.ACTIVE
    ) extends Model[FriendRequest] {
  def withId(id: Id[FriendRequest]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}
