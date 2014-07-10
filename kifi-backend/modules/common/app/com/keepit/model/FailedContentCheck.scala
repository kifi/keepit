package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class FailedContentCheck(
    id: Option[Id[FailedContentCheck]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    url1Hash: UrlHash,
    url2Hash: UrlHash,
    url1: String,
    url2: String,
    state: State[FailedContentCheck] = FailedContentCheckStates.ACTIVE,
    counts: Int,
    lastContentCheck: DateTime) extends ModelWithState[FailedContentCheck] {
  def withId(id: Id[FailedContentCheck]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[FailedContentCheck]) = copy(state = state)
  def withCounts(count: Int) = copy(counts = count)
}

object FailedContentCheckStates extends States[FailedContentCheck]

