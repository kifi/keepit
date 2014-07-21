package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class URLPattern(
    id: Option[Id[URLPattern]] = None,
    pattern: String,
    example: Option[String],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[URLPattern] = URLPatternStates.ACTIVE) extends ModelWithState[URLPattern] {
  def withId(id: Id[URLPattern]): URLPattern = copy(id = Some(id))
  def withPattern(pattern: String): URLPattern = copy(pattern = pattern)
  def withExample(example: Option[String]): URLPattern = copy(example = example)
  def withUpdateTime(when: DateTime): URLPattern = copy(updatedAt = when)
  def withState(state: State[URLPattern]): URLPattern = copy(state = state)
  def isActive = this.state == URLPatternStates.ACTIVE
}

object URLPatternStates extends States[URLPattern]
