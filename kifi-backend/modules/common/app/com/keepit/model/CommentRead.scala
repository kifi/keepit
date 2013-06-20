package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class CommentRead (
  id: Option[Id[CommentRead]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  uriId: Id[NormalizedURI],
  parentId: Option[Id[Comment]] = None,
  lastReadId: Id[Comment],
  state: State[CommentRead] = CommentReadStates.ACTIVE
) extends Model[CommentRead] {
  def withId(id: Id[CommentRead]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[CommentRead]) = copy(state = state)
  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)
  def withLastReadId(commentId: Id[Comment]) = copy(lastReadId = commentId)
}

object CommentReadStates extends States[CommentRead]
