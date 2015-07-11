package com.keepit.rover.rule

import com.keepit.common.db.Id
import com.keepit.rover.model.{ RoverHttpProxy, RoverUrlRule, HttpProxy }

sealed trait UrlRuleAction {

  def &(that: UrlRuleAction): UrlRuleAction = UrlRuleAction.And(List(this, that))
  def flatten: List[UrlRuleAction] = List(this)
}

object UrlRuleAction {

  def apply = Empty

  def fromRule(rule: RoverUrlRule) = rule.proxy.map(UseProxy.apply).getOrElse(Empty).flatten

  case object Empty extends UrlRuleAction {
    override def &(that: UrlRuleAction) = that
    override def flatten = List()
  }

  case class And(actions: List[UrlRuleAction]) extends UrlRuleAction {
    override def &(that: UrlRuleAction) = that match {
      case And(moreActions) => And(actions ++ moreActions)
      case action => And(actions :+ action)
    }
    override def flatten = actions.flatMap {
      case And(moreActions) => moreActions
      case action => List(action)
    }
  }

  case class UseProxy(proxy: Id[RoverHttpProxy]) extends UrlRuleAction

}
