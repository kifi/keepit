package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._

import org.joda.time
import org.joda.time.DateTime

case class UserCred(id: Option[Id[UserCred]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[UserCred] = UserCredStates.ACTIVE,
    userId: Id[User],
    credentials: String // TODO: char[]
    ) extends ModelWithState[UserCred] {
  def withId(id: Id[UserCred]) = this.copy(id = Some(id))
  def withUpdateTime(now: time.DateTime) = this.copy(updatedAt = now)
  def withCredentials(creds: String) = this.copy(credentials = creds)
}

object UserCred {
  val passwordHasher = "bcrypt"
}

object UserCredStates extends States[UserCred]
