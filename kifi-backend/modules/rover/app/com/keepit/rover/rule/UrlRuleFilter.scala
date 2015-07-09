package com.keepit.rover.rule

import java.util.regex.Pattern

import com.keepit.rover.model.RoverUrlRule

sealed trait UrlRuleFilter extends (String => Boolean) {

  def &(that: UrlRuleFilter): UrlRuleFilter = UrlRuleFilter.And(List(this, that))
  def |(that: UrlRuleFilter): UrlRuleFilter = UrlRuleFilter.Or(List(this, that))

}

object UrlRuleFilter {

  def apply = Empty

  def fromRule(rule: RoverUrlRule) = Regex(rule.pattern)

  case object Empty extends UrlRuleFilter {
    override def apply(url: String) = true
    override def &(that: UrlRuleFilter) = Empty
    override def |(that: UrlRuleFilter) = that

  }

  case class And(filters: List[UrlRuleFilter]) extends UrlRuleFilter {
    override def apply(url: String) = filters.forall(filter => filter(url))
    override def &(that: UrlRuleFilter) = that match {
      case And(moreFilters) => And(filters ++ moreFilters)
      case filter => And(filters :+ filter)
    }

  }

  case class Or(filters: List[UrlRuleFilter]) extends UrlRuleFilter {
    override def apply(url: String) = filters.exists(filter => filter(url))
    override def |(that: UrlRuleFilter) = that match {
      case Or(moreFilters) => Or(filters ++ moreFilters)
      case filter => Or(filters :+ filter)
    }

  }

  case class Regex(regex: String) extends UrlRuleFilter {
    val compiledPattern = Pattern.compile(regex)
    override def apply(url: String) = compiledPattern.matcher(url).matches()
  }

}
