package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime



case class FailedUriNormalization(
  id: Option[Id[FailedUriNormalization]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  urlHash: UrlHash,
  mappedUrlHash: UrlHash,
  url: String,
  mappedUrl: String,
  state: State[FailedUriNormalization] = FailedUriNormalizationStates.ACTIVE,
  failedCounts: Int
) extends Model[FailedUriNormalization] {
  def withId(id: Id[FailedUriNormalization]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[FailedUriNormalization]) = copy(state = state)
  def withCounts(count: Int) = copy(failedCounts = count)
}

object FailedUriNormalizationStates extends States[FailedUriNormalization]



