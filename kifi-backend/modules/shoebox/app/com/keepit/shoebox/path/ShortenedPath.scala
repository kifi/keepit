package com.keepit.shoebox.path

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ ModelWithPublicId, PublicIdGenerator }
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
    extends ModelWithState[ShortenedPath] with ModelWithPublicId[ShortenedPath] {
  def withId(id: Id[ShortenedPath]): ShortenedPath = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): ShortenedPath = this.copy(updatedAt = now)
  def withState(newState: State[ShortenedPath]): ShortenedPath = this.copy(state = newState)

  def isActive = state == ShortenedPathStates.ACTIVE
  def isInactive = state == ShortenedPathStates.INACTIVE

  def sanitizeForDelete = this.withState(ShortenedPathStates.INACTIVE)
}

object ShortenedPathStates extends States[ShortenedPath]

object ShortenedPath extends PublicIdGenerator[ShortenedPath] {
  override protected val publicIdIvSpec: IvParameterSpec = new IvParameterSpec(Array(-67, -41, -89, -42, 13, -18, -68, 35, 110, -24, -83, -47, 124, -32, -20, 37))
  override protected val publicIdPrefix = "sp"
}
