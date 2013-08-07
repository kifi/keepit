package com.keepit.model
import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._

/**
 * prepUrl: Typically this is NOT the raw url. It's raw url preprocessed by some simple regex rules.
 */
case class UriNormalizationRule(
  id: Option[Id[UriNormalizationRule]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  prepUrlHash: UrlHash,
  prepUrl: String,
  mappedUrl: String,
  state: State[UriNormalizationRule] = UriNormalizationRuleStates.ACTIVE
) extends Model[UriNormalizationRule] {
  def withId(id: Id[UriNormalizationRule]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UriNormalizationRule]) = copy(state = state)

}

object UriNormalizationRuleStates extends States[UriNormalizationRule]


