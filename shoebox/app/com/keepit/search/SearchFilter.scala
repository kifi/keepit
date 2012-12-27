package com.keepit.search

object SearchFilter {
  def apply(spec: Option[String]): SearchFilter = apply(spec.getOrElse("mfo")) // the default is mine+friends+others

  def apply(implicit spec: String): SearchFilter = new SearchFilter('m', ('m' || 'f') , 'f', 'o')

  implicit private def toBoolean(c: Char)(implicit spec: String): Boolean = spec.indexOf(c) >= 0
}

case class SearchFilter(mine: Boolean, shared: Boolean, friends: Boolean, others: Boolean)