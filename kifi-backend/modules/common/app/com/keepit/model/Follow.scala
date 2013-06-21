package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class Follow (
  id: Option[Id[Follow]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  uriId: Id[NormalizedURI],
  urlId: Option[Id[URL]] = None,
  state: State[Follow] = FollowStates.ACTIVE
) extends Model[Follow] {
  def withId(id: Id[Follow]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def activate = copy(state = FollowStates.ACTIVE)
  def deactivate = copy(state = FollowStates.INACTIVE)
  def isActive = state == FollowStates.ACTIVE
  def withUrlId(urlId: Id[URL]) = copy(urlId = Some(urlId))
  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)
}

object FollowStates extends States[Follow]
