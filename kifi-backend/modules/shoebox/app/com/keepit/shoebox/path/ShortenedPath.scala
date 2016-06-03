package com.keepit.shoebox.path

import com.keepit.common.db.{ States, State, ModelWithState, Id }
import com.keepit.common.path.Path
import org.joda.time.DateTime
import com.keepit.common.time._

final case class ShortenedPath(
  id: Option[Id[ShortenedPath]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[ShortenedPath] = ShortenedPathStates.ACTIVE,
  path: Path)
    extends ModelWithState[ShortenedPath] {
  def withId(id: Id[ShortenedPath]): ShortenedPath = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): ShortenedPath = this.copy(updatedAt = now)
  def withState(newState: State[ShortenedPath]): ShortenedPath = this.copy(state = newState)

  def isActive = state == ShortenedPathStates.ACTIVE
  def isInactive = state == ShortenedPathStates.INACTIVE

  def sanitizeForDelete = this.withState(ShortenedPathStates.INACTIVE)
}

object ShortenedPathStates extends States[ShortenedPath]
