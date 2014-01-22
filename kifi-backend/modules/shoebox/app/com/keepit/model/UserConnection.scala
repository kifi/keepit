package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.db.State
import com.keepit.common.db.States
import com.keepit.common.time._
import com.keepit.common.time.currentDateTime

case class UserConnection(
    id: Option[Id[UserConnection]] = None,
    user1: Id[User],
    user2: Id[User],
    state: State[UserConnection] = UserConnectionStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime
  ) extends Model[UserConnection] {
  def withId(id: Id[UserConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserConnection]) = copy(state = state)
}

object UserConnectionStates extends States[UserConnection] {
  val UNFRIENDED = State[UserConnection]("unfriended")
}

