package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.mail.EmailAddress

case class PasswordReset(
    id: Option[Id[PasswordReset]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    state: State[PasswordReset] = PasswordResetStates.ACTIVE,
    token: String,
    usedAt: Option[DateTime] = None,
    usedByIP: Option[String] = None,
    sentTo: Option[String] = None) extends ModelWithState[PasswordReset] {
  def withId(id: Id[PasswordReset]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[PasswordReset]) = copy(state = state)
}

object PasswordResetStates extends States[PasswordReset] {
  // ACTIVE means unused, user is pending a reset. However, it may be expired.
  val USED = State[PasswordReset]("used")
}
