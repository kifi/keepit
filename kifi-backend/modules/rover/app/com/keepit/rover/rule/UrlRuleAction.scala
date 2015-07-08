package com.keepit.rover.rule

import com.keepit.common.db.Id
import com.keepit.rover.model.HttpProxy

sealed trait UrlRuleAction {

  def &(that: UrlRuleAction): UrlRuleAction = UrlRuleAction.And(List(this, that))

}

object UrlRuleAction {

  def apply = Empty

  case object Empty extends UrlRuleAction {
    override def &(that: UrlRuleAction) = that
  }

  case class And(actions: List[UrlRuleAction]) extends UrlRuleAction {
    override def &(that: UrlRuleAction) = And(actions :+ that)
  }

  case class UseProxy(proxy: Id[HttpProxy]) extends UrlRuleAction

  case class NormalizeScheme(normalization: String) extends UrlRuleAction

  case object PreventScrape extends UrlRuleAction

  case class StaySensitive(sensitive: Boolean) extends UrlRuleAction

}
