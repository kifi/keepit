package com.keepit.rover.model

import com.keepit.common.db.{ModelWithState, State, Id, States}
import com.keepit.common.time._
import com.keepit.model.HttpProxy
import com.keepit.rover.rule.{UrlRuleAction, UrlRuleFilter}
import org.joda.time.DateTime

object RoverUrlRuleStates extends States[RoverUrlRule]

case class RoverUrlRule(
  id: Option[Id[RoverUrlRule]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[RoverUrlRule] = RoverUrlRuleStates.ACTIVE,
  filter: UrlRuleFilter,
  action: UrlRuleAction,
  useProxy: Option[Id[HttpProxy]]) extends ModelWithState[RoverUrlRule] {

  override def withId(id: Id[RoverUrlRule]): RoverUrlRule = this.copy(id = Some(id))
  override def withUpdateTime(now: DateTime): RoverUrlRule = this.copy(updatedAt = now)
  def isActive = this.state == RoverUrlRuleStates.ACTIVE

}

object RoverUrlRule {

  def applyFromDbRow(
    id: Option[Id[RoverUrlRule]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[RoverUrlRule] = RoverUrlRuleStates.ACTIVE,
    pattern: UrlRuleFilter,
    action: UrlRuleAction,
    useProxy: Option[Id[HttpProxy]]) = RoverUrlRule(id, createdAt, updatedAt, state, pattern, useProxy)

}
