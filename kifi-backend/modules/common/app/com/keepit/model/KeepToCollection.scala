package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._

case class KeepToCollection(
  id: Option[Id[KeepToCollection]] = None,
  bookmarkId: Id[Bookmark],
  collectionId: Id[Collection],
  state: State[KeepToCollection] = KeepToCollectionStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
  ) extends Model[KeepToCollection] {
  def isActive: Boolean = state == KeepToCollectionStates.ACTIVE
  def withId(id: Id[KeepToCollection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object KeepToCollectionStates extends States[KeepToCollection]
