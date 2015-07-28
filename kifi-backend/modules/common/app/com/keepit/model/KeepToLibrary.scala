package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class KeepToLibrary(
  id: Option[Id[KeepToLibrary]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KeepToLibrary] = KeepToLibraryStates.ACTIVE,
  keepId: Id[Keep],
  libraryId: Id[Library])
    extends ModelWithState[KeepToLibrary] {

  def withId(id: Id[KeepToLibrary]): KeepToLibrary = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepToLibrary = this.copy(updatedAt = now)
  def withState(newState: State[KeepToLibrary]): KeepToLibrary = this.copy(state = newState)
  def sanitizeForDelete: KeepToLibrary = this.copy(
    state = KeepToLibraryStates.INACTIVE
  )

  def isActive = state == KeepToLibraryStates.ACTIVE
}

object KeepToLibraryStates extends States[KeepToLibrary]
