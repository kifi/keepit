package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json._

case class SliderRule(
    id: Option[Id[SliderRule]] = None,
    groupName: String,
    name: String,
    parameters: Option[JsArray] = None,
    state: State[SliderRule] = SliderRuleStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithState[SliderRule] {
  def withId(id: Id[SliderRule]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[SliderRule]) = this.copy(state = state)
  def withParameters(parameters: Option[JsArray]) = this.copy(parameters = parameters)
  def isActive = this.state == SliderRuleStates.ACTIVE
}

object SliderRuleStates extends States[SliderRule]

case class SliderRuleGroup(rules: Seq[SliderRule]) {
  lazy val updatedAt: DateTime = rules.map(_.updatedAt).max
  lazy val version: String = SliderRuleGroup.version(updatedAt)
  lazy val compactJson = JsObject(Seq(
    "version" -> JsString(version),
    "rules" -> JsObject(rules.filter(_.isActive).map { r => r.name -> r.parameters.getOrElse(JsNumber(1)) })))
}

object SliderRuleGroup {
  def version(updatedAt: DateTime): String = java.lang.Long.toString(updatedAt.getMillis, 36)
}
