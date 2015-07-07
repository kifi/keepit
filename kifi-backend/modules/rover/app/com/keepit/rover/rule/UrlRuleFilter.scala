package com.keepit.rover.rule

sealed trait UrlRuleFilter extends (String => Boolean) {

  def &(that: UrlRuleFilter): UrlRuleFilter = UrlRuleFilter.And(List(this, that))

  def |(that: UrlRuleFilter): UrlRuleFilter = UrlRuleFilter.Or(List(this, that))

}

object UrlRuleFilter {

  def apply = Empty

  case object Empty extends UrlRuleFilter {

    override def apply(url: String) = true

    override def &(that: UrlRuleFilter) = Empty

    override def |(that: UrlRuleFilter) = that

  }

  case class And(filters: List[UrlRuleFilter]) extends UrlRuleFilter {

    override def apply(url: String) = filters.forall(filter => filter(url))

    override def &(that: UrlRuleFilter) = And(filters :+ that)

  }

  case class Or(filters: List[UrlRuleFilter]) extends UrlRuleFilter {

    override def apply(url: String) = filters.exists(filter => filter(url))

    override def |(that: UrlRuleFilter) = Or(filters :+ that)

  }

  case class Regex(pattern: String) extends UrlRuleFilter {

    override def apply(url: String) = url.matches(pattern)

  }

}
