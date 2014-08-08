package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._

/**
 * given (user, uri) pair, we can query how many times this uri has been clicked.
 * We assume this uri is one of the user's keeps.
 * Counts are incremented by ResultClickedListener.
 */

case class UserBookmarkClicks(
    id: Option[Id[UserBookmarkClicks]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    uriId: Id[NormalizedURI],
    selfClicks: Int, // clicked by self
    otherClicks: Int, // clicked by other user
    rekeepCount: Int = 0,
    rekeepTotalCount: Int = 0,
    rekeepDegree: Int = 0) extends Model[UserBookmarkClicks] {
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[UserBookmarkClicks]) = this.copy(id = Some(id))
}

