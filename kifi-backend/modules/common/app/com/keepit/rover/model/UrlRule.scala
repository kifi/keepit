package com.keepit.rover.model

import com.keepit.common.db.{ States, State, Id }

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class UrlRule(
    id: Option[Id[UrlRule]] = None,
    state: State[UrlRule] = UrlRuleStates.ACTIVE,
    pattern: String,
    proxy: Option[Id[HttpProxy]]) {

  def isActive = state == UrlRuleStates.ACTIVE

}

object UrlRuleStates extends States[UrlRule]

object UrlRule {

  implicit val format = (
    (__ \ 'id).formatNullable[Id[UrlRule]] and
    (__ \ 'state).format[State[UrlRule]] and
    (__ \ 'pattern).format[String] and
    (__ \ 'proxy).formatNullable[Id[HttpProxy]]
  )(UrlRule.apply, unlift(UrlRule.unapply))

}
