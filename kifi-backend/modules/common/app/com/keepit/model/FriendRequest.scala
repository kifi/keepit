package com.keepit.model

import com.keepit.eliza.model.MessageHandle
import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.Some

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

object FriendRequest {
  implicit val friendRequestIdFormat = Id.format[FriendRequest]
  implicit val userIdFormat = Id.format[User]
  implicit val messageHandleIdFormat = Id.format[MessageHandle]
  implicit val stateFormat = State.format[FriendRequest]

  implicit val friendRequestFormat = (
    (__ \ 'id).format[Option[Id[FriendRequest]]] and
    (__ \ 'senderId).format[Id[User]] and
    (__ \ 'recipientId).format[Id[User]] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format[State[FriendRequest]] and
    (__ \ 'messageHandle).format[Option[Id[MessageHandle]]]
  )(FriendRequest.apply, unlift(FriendRequest.unapply))
}
