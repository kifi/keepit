package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class KeepToUser(
  id: Option[Id[KeepToUser]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KeepToUser] = KeepToUserStates.ACTIVE,
  keepId: Id[Keep],
  userId: Id[User],
  addedAt: DateTime = currentDateTime,
  addedBy: Id[User],
  // A denormalized field from Keep
  uriId: Id[NormalizedURI])
    extends ModelWithState[KeepToUser] {

  def withId(id: Id[KeepToUser]): KeepToUser = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepToUser = this.copy(updatedAt = now)
  def withState(newState: State[KeepToUser]): KeepToUser = this.copy(state = newState)
  def withUriId(newUriId: Id[NormalizedURI]) = this.copy(uriId = newUriId)
  def withAddedAt(time: DateTime) = this.copy(addedAt = time)

  def isActive = state == KeepToUserStates.ACTIVE
  def isInactive = state == KeepToUserStates.INACTIVE

  def sanitizeForDelete = this.withState(KeepToUserStates.INACTIVE)
}

object KeepToUserStates extends States[KeepToUser]
