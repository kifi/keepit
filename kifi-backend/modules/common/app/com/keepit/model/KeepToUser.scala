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

  def isActive = state == KeepToUserStates.ACTIVE
  def isInactive = state == KeepToUserStates.INACTIVE

  def sanitizeForDelete = this.withState(KeepToUserStates.INACTIVE)
}

object KeepToUser {
  def applyFromDbRow(id: Option[Id[KeepToUser]], createdAt: DateTime, updatedAt: DateTime, state: State[KeepToUser],
    keepId: Id[Keep], userId: Id[User], addedAt: DateTime, addedBy: Id[User],
    uriId: Id[NormalizedURI]): KeepToUser = {
    KeepToUser(
      id, createdAt, updatedAt, state,
      keepId, userId, addedAt, addedBy,
      uriId)
  }

  def unapplyToDbRow(ktu: KeepToUser) = {
    Some(
      (ktu.id, ktu.createdAt, ktu.updatedAt, ktu.state,
        ktu.keepId, ktu.userId, ktu.addedAt, ktu.addedBy,
        ktu.uriId)
    )
  }
}

object KeepToUserStates extends States[KeepToUser]
