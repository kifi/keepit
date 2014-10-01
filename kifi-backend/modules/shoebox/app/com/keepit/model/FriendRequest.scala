package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.eliza.model.MessageHandle
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class FriendRequest(
    id: Option[Id[FriendRequest]] = None,
    senderId: Id[User],
    recipientId: Id[User],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[FriendRequest] = FriendRequestStates.ACTIVE,
    messageHandle: Option[Id[MessageHandle]]) extends ModelWithState[FriendRequest] {
  def withId(id: Id[FriendRequest]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object FriendRequestStates extends States[FriendRequest] {
  val ACCEPTED = State[FriendRequest]("accepted")
  val IGNORED = State[FriendRequest]("ignored")
}
