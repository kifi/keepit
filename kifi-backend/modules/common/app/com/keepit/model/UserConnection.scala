package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.time.currentDateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class UserConnection(
    id: Option[Id[UserConnection]] = None,
    user1: Id[User],
    user2: Id[User],
    state: State[UserConnection] = UserConnectionStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    seq: SequenceNumber[UserConnection] = SequenceNumber.ZERO) extends ModelWithState[UserConnection] with ModelWithSeqNumber[UserConnection] {
  def withId(id: Id[UserConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserConnection]) = copy(state = state)
}

object UserConnection {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[UserConnection]) and
    (__ \ 'user1).format(Id.format[User]) and
    (__ \ 'user2).format(Id.format[User]) and
    (__ \ 'state).format(State.format[UserConnection]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'seq).format(SequenceNumber.format[UserConnection])
  )(UserConnection.apply, unlift(UserConnection.unapply))
}

object UserConnectionStates extends States[UserConnection] {
  val UNFRIENDED = State[UserConnection]("unfriended")
}
