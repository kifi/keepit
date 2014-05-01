package com.keepit.model


import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._

case class ReKeep(
  id: Option[Id[ReKeep]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[ReKeep] = ReKeepState.ACTIVE,

  keeperId: Id[User],
  keepId: Id[Keep],
  uriId: Id[NormalizedURI],

  srcUserId: Id[User],
  srcKeepId: Id[Keep],

  attributionFactor: Int = 1

) extends ModelWithState[ReKeep] {
  def withId(id: Id[ReKeep]): ReKeep = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): ReKeep = this.copy(updatedAt = now)
}

object ReKeepState extends States[ReKeep]
